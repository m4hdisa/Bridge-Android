// Writes Icons.kt for Bridge for Android from the icon sets the Windows app uses:
// Lucide (ISC) for everything, Phosphor (MIT) for the navigation bar, as in the Windows sidebar.
// Usage, with Bridge-app checked out and its packages installed:
//   node tools/gen-icons.mjs ../bridge-app app/src/main/java/io/github/m4hdisa/bridge/ui/Icons.kt
import { pathToFileURL } from 'node:url'
import { writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'

const [appDir, out] = process.argv.slice(2)
const require = createRequire(path.join(appDir, 'package.json'))

const lucide = {
  Power: 'power', Zap: 'zap', Plus: 'plus', RefreshCw: 'refresh-cw', Ellipsis: 'ellipsis', Link2: 'link-2',
  Shield: 'shield', ShieldCheck: 'shield-check', ShieldAlert: 'shield-alert', Check: 'check', CircleCheck: 'circle-check',
  CircleAlert: 'circle-alert', X: 'x', Copy: 'copy', ClipboardPaste: 'clipboard-paste', ChevronRight: 'chevron-right',
  ChevronDown: 'chevron-down', Search: 'search', Trash2: 'trash', Globe: 'globe', Wifi: 'wifi', Info: 'info',
  TriangleAlert: 'triangle-alert', Split: 'split', Download: 'download', Share2: 'share-2', Server: 'server',
  ScrollText: 'scroll-text', Languages: 'languages', MapPin: 'map-pin', Activity: 'activity', Cloud: 'cloud',
  Waypoints: 'waypoints', Ban: 'ban', ArrowRight: 'arrow-right', Gauge: 'gauge', Sparkles: 'sparkles', Pencil: 'pencil',
  KeyRound: 'key-round', Smartphone: 'smartphone', LoaderCircle: 'loader-circle',
  ArrowDown: 'arrow-down', ArrowUp: 'arrow-up', QrCode: 'qr-code', ScanLine: 'scan-line',
}
const phosphor = { SquaresFour: 'SquaresFour', GlobeHemisphereWest: 'GlobeHemisphereWest', AppWindow: 'AppWindow', Scroll: 'Scroll', GearSix: 'GearSix' }

const n = (v) => Number(v)
/**
 * Rewrites path data with every number spelled out, since SVG lets arc flags run together
 * ("a1.5 1.5 0 00-2.4") and not every Android path parser reads that.
 */
function normalize(d) {
  const counts = { m: 2, l: 2, h: 1, v: 1, c: 6, s: 4, q: 4, t: 2, a: 7, z: 0 }
  const out = []
  let i = 0
  let cmd = null
  const num = () => {
    const m = /^[\s,]*([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)/.exec(d.slice(i))
    if (!m) throw new Error(`bad path near "${d.slice(i, i + 12)}"`)
    i += m[0].length
    return m[1]
  }
  const flag = () => {
    const m = /^[\s,]*([01])/.exec(d.slice(i))
    if (!m) throw new Error(`bad arc flag near "${d.slice(i, i + 12)}"`)
    i += m[0].length
    return m[1]
  }
  while (i < d.length) {
    const rest = d.slice(i)
    const c = /^[\s,]*([MmLlHhVvCcSsQqTtAaZz])/.exec(rest)
    if (c) { cmd = c[1]; i += c[0].length; out.push(cmd); if (cmd.toLowerCase() === 'z') continue }
    else if (/^[\s,]*$/.test(rest)) break
    else if (!cmd) throw new Error('path without a command')
    const lower = cmd.toLowerCase()
    const args = []
    for (let k = 0; k < counts[lower]; k++) args.push(lower === 'a' && (k === 3 || k === 4) ? flag() : num())
    out.push(args.join(' '))
  }
  return out.join(' ').replace(/ ([MmLlHhVvCcSsQqTtAaZz])/g, '$1')
}

function toPath(tag, a) {
  return normalize(rawPath(tag, a))
}

function rawPath(tag, a) {
  switch (tag) {
    case 'path': return a.d
    case 'circle': { const [cx, cy, r] = [n(a.cx), n(a.cy), n(a.r)]; return `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0` }
    case 'ellipse': { const [cx, cy, rx, ry] = [n(a.cx), n(a.cy), n(a.rx), n(a.ry)]; return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0` }
    case 'line': return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`
    case 'polyline': case 'polygon': { const p = a.points.trim().split(/[\s,]+/).map(Number); let d = `M${p[0]} ${p[1]}`; for (let i = 2; i < p.length; i += 2) d += `L${p[i]} ${p[i + 1]}`; return tag === 'polygon' ? d + 'Z' : d }
    case 'rect': {
      const [x, y, w, h] = [n(a.x ?? 0), n(a.y ?? 0), n(a.width), n(a.height)]
      const r = Math.min(n(a.rx ?? a.ry ?? 0), w / 2, h / 2)
      if (!r) return `M${x} ${y}h${w}v${h}h${-w}Z`
      return `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${h - 2 * r}a${r} ${r} 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a${r} ${r} 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a${r} ${r} 0 0 1 ${r} ${-r}Z`
    }
    default: throw new Error(`unknown element ${tag}`)
  }
}

const lines = []
for (const [name, file] of Object.entries(lucide)) {
  const mod = await import(pathToFileURL(path.join(appDir, 'node_modules/lucide-react/dist/esm/icons', `${file}.mjs`)).href)
  const paths = mod.__iconData.node.map(([tag, attrs]) => toPath(tag, attrs))
  lines.push(`    val ${name}: ImageVector by lazy { stroke("${file}", ${paths.map((p) => JSON.stringify(p)).join(', ')}) }`)
}
const React = require('react')
function collect(el, acc) {
  if (!el || typeof el !== 'object') return acc
  if (Array.isArray(el)) { el.forEach((c) => collect(c, acc)); return acc }
  if (el.type === 'path') acc.push(el.props.d)
  else collect(el.props?.children, acc)
  return acc
}
for (const [name, file] of Object.entries(phosphor)) {
  const mod = await import(pathToFileURL(path.join(appDir, 'node_modules/@phosphor-icons/react/dist/defs', `${file}.mjs`)).href)
  for (const weight of ['regular', 'fill']) {
    const paths = collect(mod.default.get(weight), []).map(normalize)
    lines.push(`    val ${name}${weight === 'fill' ? 'Fill' : ''}: ImageVector by lazy { fill("${file}-${weight}", ${paths.map((p) => JSON.stringify(p)).join(', ')}) }`)
  }
}
void React

writeFileSync(out, `package io.github.m4hdisa.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons the Windows app uses: Lucide (ISC License, lucide.dev) and, for the navigation bar,
 * Phosphor (MIT License, phosphoricons.com). Generated from their packages; don't edit by hand.
 */
object Icons {
${lines.join('\n')}

    /** Lucide: drawn with a 2-unit line on a 24-unit grid. */
    private fun stroke(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    /** Phosphor: filled shapes on a 256-unit grid. */
    private fun fill(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 256f, 256f).apply {
        for (d in paths) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()
}
`)
console.log(`wrote ${lines.length} icons`)
