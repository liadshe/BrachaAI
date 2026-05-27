import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from '@pages/LoginPage';
import SignupPage from '@pages/SignupPage';
import HomePage from '@pages/HomePage';
import '@/styles/global.css';


function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LoginPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/home" element={<HomePage />} />
        <Route path="/clients" element={<HomePage />} /> {/* Placeholder */}
        <Route path="/tasks" element={<HomePage />} />   {/* Placeholder */}
        <Route path="/settings" element={<HomePage />} /> {/* Placeholder */}
        <Route path="*" element={<Navigate to="/" replace />} />

      </Routes>
    </BrowserRouter>
  );
}

export default App;
