import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from '@pages/LoginPage';
import SignupPage from '@pages/SignupPage';
import HomePage from '@pages/HomePage';
import TasksPage from '@pages/TasksPage';
import SettingsPage from '@pages/SettingsPage';
import ContactsPage from '@pages/ContactsPage';
import ContactDetailsPage from '@pages/ContactDetailsPage';
import EditProfilePage from '@pages/EditProfilePage';
import ProtectedRoute from '@components/ProtectedRoute';
import '@/styles/global.css';




function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LoginPage />} />
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
    </BrowserRouter>
  );
}


export default App;
