import { useEffect, useRef, useState, type KeyboardEvent } from 'react'

// Body of GET /api/search and GET /api/latest (backend: controller/dto/SearchResponse)
type Car = {
  make: string; model: string; year: number; bodyType: string; vehicleStyle: string; fuel: string
  drivetrain: string; transmission: string; engineHp: number | null; highwayMpg: number | null
}
type Score = { total: number; semantic: number; keyword: number; constraints: number; diversityPenalty: number }
type Row = { car: Car; trimCount: number; msrpMin: number | null; msrpMax: number | null; matched: string[]; score: Score | null }
type Result = {
  query: string; filters: { label: string; type: 'filter' | 'preference' | 'guess' }[]
  totalGroups: number; ranked: boolean; message: string | null; tookMs: number; results: Row[]
}

const EXAMPLES = ['trucks', 'electric SUV under 60k', 'family car with lots of space', 'fast convertible', 'Civic', 'suv not electric']

const money = (n: number) => '$' + n.toLocaleString('en-US')

export default function App() {
  const [input, setInput] = useState(new URLSearchParams(location.search).get('q') ?? '')
  const [query, setQuery] = useState('')
  const [data, setData] = useState<Result | null>(null)
  const [status, setStatus] = useState('')   // '', 'searching', 'waking', or an error message
  const [page, setPage] = useState(0)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [active, setActive] = useState(-1)   // highlighted suggestion, -1 = none
  const timer = useRef(0)
  const typed = useRef<string | null>(null)  // last typed text; late answers for older text are ignored
  const current = useRef('')                 // query being shown; late answers for older queries are ignored

  // search-as-you-type: ask the server 150 ms after the last key press
  function type(value: string) {
    setInput(value)
    typed.current = value
    clearTimeout(timer.current)
    timer.current = window.setTimeout(async () => {
      const res = await fetch('/api/suggest?q=' + encodeURIComponent(value)).catch(() => null)
      const list = res?.ok ? await res.json() : []
      if (typed.current === value) { setSuggestions(list); setActive(-1) }
    }, 150)
  }

  function onKey(e: KeyboardEvent) {
    if (suggestions.length === 0) return
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((active + 1) % suggestions.length) }
    if (e.key === 'ArrowUp') { e.preventDefault(); setActive((active - 1 + suggestions.length) % suggestions.length) }
    if (e.key === 'Escape') setSuggestions([])
    if (e.key === 'Enter' && active >= 0) { e.preventDefault(); search(suggestions[active]) }
  }

  async function fetchPage(q: string, p: number) {
    setStatus('searching')
    const started = Date.now()
    while (true) {
      try {
        const res = await fetch(q ? `/api/search?q=${encodeURIComponent(q)}&page=${p}&size=20` : `/api/latest?page=${p}&size=20`)
        const body = await res.json().catch(() => ({}))
        if (current.current !== q) return
        // the server may still be starting (503 while the model loads, or the request fails): retry for 90 s
        if (res.status === 503 || res.status === 502) throw new Error('waking')
        if (!res.ok) { setStatus(body.error ?? 'Something went wrong'); return }
        setData(prev => (p > 0 && prev ? { ...body, results: [...prev.results, ...body.results] } : body))
        setStatus('')
        return
      } catch {
        if (current.current !== q) return
        if (Date.now() - started > 90_000) { setStatus('Server did not respond, try again later'); return }
        setStatus('waking')
        await new Promise(r => setTimeout(r, 5000))
      }
    }
  }

  function search(q: string, pushUrl = true) {
    q = q.trim()
    typed.current = null
    setSuggestions([])
    setInput(q)
    setQuery(q)
    current.current = q
    setPage(0)
    setData(null)
    if (pushUrl) history.pushState(null, '', q ? '?q=' + encodeURIComponent(q) : '/')
    fetchPage(q, 0)
  }

  // search on load and on back/forward, using ?q= from the URL
  useEffect(() => {
    const fromUrl = () => search(new URLSearchParams(location.search).get('q') ?? '', false)
    fromUrl()
    window.addEventListener('popstate', fromUrl)
    return () => window.removeEventListener('popstate', fromUrl)
  }, [])

  return (
    <>
      <header>
        <form onSubmit={e => { e.preventDefault(); search(input) }}>
          <div className="box">
            <input value={input} onChange={e => type(e.target.value)} onKeyDown={onKey} onBlur={() => setSuggestions([])}
              placeholder="Search cars, e.g. electric SUV under 60k" maxLength={200} autoFocus autoComplete="off" />
            {suggestions.length > 0 && (
              <ul className="suggest">
                {suggestions.map((s, i) => (
                  // onMouseDown (not onClick) so it fires before the input's blur closes the list
                  <li key={s} className={i === active ? 'active' : ''} onMouseDown={() => search(s)}>{s}</li>
                ))}
              </ul>
            )}
          </div>
          {/* an empty search goes back to the latest cars */}
          <button className="yellow">Search inventory</button>
        </form>
      </header>
      <nav>{EXAMPLES.map(ex => <button key={ex} className="pill" onClick={() => search(ex)}>{ex}</button>)}</nav>

      <main>
        {status === 'searching' && <p>Searching...</p>}
        {status === 'waking' && <p>The server is starting up, this can take up to a minute...</p>}
        {status && status !== 'searching' && status !== 'waking' && <p className="error">{status}</p>}
        {!query && <h2>Semantic Car Search <span className="muted">· about 11,000 cars, searched by meaning</span></h2>}

        {!query && data && <h3>Latest cars</h3>}
        {data && (
          <>
            {query && <>
              <p className="crumbs">Home / Car search / {query}</p>
              <h2><span className="count">{data.totalGroups}</span> Search results for: {query}
                <span className="muted"> · {data.tookMs} ms</span></h2>
            </>}
            {data.message && <p>{data.message}</p>}

            {data.results.length > 0 && (
              <table>
                <thead><tr><th>#</th><th>Vehicle</th><th>Vehicle info</th><th>Specs</th><th>Price (MSRP)</th>{data.ranked && <th>Score</th>}</tr></thead>
                <tbody>
                  {data.results.map((g, i) => {
                    const c = g.car
                    return (
                      <tr key={c.make + c.model + c.year}>
                        <td>{i + 1}</td>
                        <td><a>{c.year} {c.make} {c.model}</a><div className="muted">{g.trimCount} trims</div></td>
                        <td>{c.bodyType}<div className="muted">{c.vehicleStyle}</div></td>
                        <td>{[c.fuel, c.drivetrain, c.transmission?.toLowerCase(), c.engineHp && c.engineHp + ' hp',
                          c.highwayMpg && c.highwayMpg + ' mpg hwy'].filter(Boolean).join(' · ')}</td>
                        <td><b>{g.msrpMin == null ? 'Price unavailable'
                          : g.msrpMin === g.msrpMax ? money(g.msrpMin) : `${money(g.msrpMin)} – ${money(g.msrpMax!)}`}</b></td>
                        {data.ranked && g.score && <td>
                          <details>
                            <summary>{g.score.total.toFixed(3)}</summary>
                            semantic {g.score.semantic.toFixed(2)}<br />keyword {g.score.keyword.toFixed(2)}<br />
                            constraints {g.score.constraints.toFixed(2)}
                            {g.score.diversityPenalty > 0 && <><br />diversity −{g.score.diversityPenalty.toFixed(3)}</>}
                          </details>
                          {g.matched.map(m => <div key={m} className="ok">✓ {m}</div>)}
                        </td>}
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}

            {data.results.length < data.totalGroups && (
              <button className="blue" onClick={() => { setPage(page + 1); fetchPage(query, page + 1) }}>Load more</button>
            )}
          </>
        )}
      </main>
    </>
  )
}
