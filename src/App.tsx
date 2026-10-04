import { DesktopAppShell } from './components/desktop/DesktopAppShell';
import { MobileAppShell } from './components/mobile/MobileAppShell';
import { I18nProvider } from './i18n/I18nContext';
import { useMediaQuery } from './hooks/useMediaQuery';
import './App.css';

// Phones and tablets get the tab-bar app; the rail + panel desktop layout
// needs ≥1024px (600–1023px was left open by the design; the mobile shell
// scales up cleanly, the desktop one doesn't scale down).
const MOBILE_QUERY = '(max-width: 1023px)';

function App() {
  const isMobile = useMediaQuery(MOBILE_QUERY);
  return <I18nProvider>{isMobile ? <MobileAppShell /> : <DesktopAppShell />}</I18nProvider>;
}

export default App;
