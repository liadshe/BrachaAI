import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from '@pages/LoginPage';
import SignupPage from '@pages/SignupPage';
import HomePage from '@pages/HomePage';
import TasksPage from '@pages/TasksPage';
import SettingsPage from '@pages/SettingsPage';
import ClientsPage from '@pages/ClientsPage';
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
        <Route path="/clients" element={<ProtectedRoute><ClientsPage /></ProtectedRoute>} />
        <Route path="/tasks" element={<ProtectedRoute><TasksPage /></ProtectedRoute>} />
        <Route path="/settings" element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />
        <Route path="*" element={<Navigate to="/" replace />} />


      </Routes>
    </BrowserRouter>
  );
}

export default App;
