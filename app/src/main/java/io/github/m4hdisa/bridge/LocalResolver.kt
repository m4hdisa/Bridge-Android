package io.github.m4hdisa.bridge

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Lets sing-box's "local" DNS server ask the real network's DNS; only used to find the VPN server itself. */
class LocalResolver(private val network: () -> Network?) : LocalDNSTransport {
    private val executor = Executors.newCachedThreadPool()

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun currentNetwork() = network() ?: error("no network")

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        ctx.onCancel {
            signal.cancel()
            done.countDown()
        }
        DnsResolver.getInstance().rawQuery(
            currentNetwork(),
            message,
            DnsResolver.FLAG_NO_RETRY,
            executor,
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    fail(ctx, error)
                    done.countDown()
                }
            },
        )
        done.await()
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lookupWithResolver(ctx, network, domain)
            return
        }
        val answer = try {
            currentNetwork().getAllByName(domain)
        } catch (_: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lookupWithResolver(ctx: ExchangeContext, network: String, domain: String) {
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        ctx.onCancel {
            signal.cancel()
            done.countDown()
        }
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                if (rcode == 0) ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n")) else ctx.errorCode(rcode)
                done.countDown()
            }

            override fun onError(error: DnsResolver.DnsException) {
                fail(ctx, error)
                done.countDown()
            }
        }
        val type = when {
            network.endsWith("4") -> DnsResolver.TYPE_A
            network.endsWith("6") -> DnsResolver.TYPE_AAAA
            else -> null
        }
        if (type != null) {
            DnsResolver.getInstance().query(currentNetwork(), domain, type, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
        } else {
            DnsResolver.getInstance().query(currentNetwork(), domain, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
        }
        done.await()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun fail(ctx: ExchangeContext, error: DnsResolver.DnsException) {
        val cause = error.cause
        ctx.errnoCode(if (cause is ErrnoException) cause.errno else EIO)
    }

    private companion object {
        const val RCODE_NXDOMAIN = 3
        const val EIO = 5
    }
}
