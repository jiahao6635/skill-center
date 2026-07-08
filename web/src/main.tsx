import ReactDOM from 'react-dom/client'
import { App } from './app/providers.tsx'
import './i18n/config.ts'
import './index.css'

/**
 * Main React entry point.
 *
 * Runtime configuration is loaded earlier in `bootstrap.ts`; this file only mounts the configured
 * application tree.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <App />,
)
