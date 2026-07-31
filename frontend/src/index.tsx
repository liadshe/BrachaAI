import { StrictMode } from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.tsx'
import { hydrateSessionFromNative } from './services/authTokens'

// Before React renders: AuthLanding decides the whole startup route on whether a token is
// present, and file:// localStorage does not survive the app process being killed. Native
// holds the durable copy, so restore from it first or a returning user lands on login.
hydrateSessionFromNative()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// Register service worker for PWA support
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/service-worker.js')
      .then(reg => console.log('Service Worker registered:', reg))
      .catch(err => console.log('Service Worker registration failed:', err))
  })
}
