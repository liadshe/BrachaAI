import { HashRouter , Routes, Route, Navigate } from 'react-router-dom';

import LoginPage from '@pages/LoginPage';
import SignupPage from '@pages/SignupPage';
import HomePage from '@pages/HomePage';
import TasksPage from '@pages/TasksPage';
import SettingsPage from '@pages/SettingsPage';
import ContactsPage from '@pages/ContactsPage';
import ContactDetailsPage from '@pages/ContactDetailsPage';
import EditProfilePage from '@pages/EditProfilePage';
import ProtectedRoute from '@components/ProtectedRoute';
import AuthLanding from '@components/AuthLanding';
import '@/styles/global.css';


function App() {
  // Native holds its own token pair, provisioned at login via /auth/device-token.
  // Pushing the web token here would overwrite it with credentials that rotate
  // independently, breaking background uploads.

  return (
    <HashRouter>
      <Routes>
        <Route path="/" element={<AuthLanding />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/home" element={<ProtectedRoute><HomePage /></ProtectedRoute>} />
        <Route path="/contacts" element={<ProtectedRoute><ContactsPage /></ProtectedRoute>} />
        <Route path="/contacts/:id" element={<ProtectedRoute><ContactDetailsPage /></ProtectedRoute>} />
        <Route path="/tasks" element={<ProtectedRoute><TasksPage /></ProtectedRoute>} />
        <Route path="/settings" element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />
        <Route path="/edit-profile" element={<ProtectedRoute><EditProfilePage /></ProtectedRoute>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </HashRouter>
  );
}

export default App;
