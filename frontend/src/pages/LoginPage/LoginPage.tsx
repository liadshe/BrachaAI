import { useState, FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import styles from './LoginPage.module.css';

const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const navigate = useNavigate();

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    console.log('Login submitted', { email, password });
    navigate('/home');
  };


  return (
    <div className={styles.pageWrapper}>
      <div className={styles.card}>
        {/* Logo Section */}
        <div className={styles.logoSection}>
          <div className={styles.logoBox}>
            <img src="/logo.jpg" alt="Bracha AI" className={styles.logoImage} />
            <div className={styles.logoTextContainer}>
              <span className={styles.logoMainText}>BRACHA.AI</span>
              <span className={styles.logoSubText}>BECAUSE BRACHA REMEMBERS.</span>
            </div>
          </div>
        </div>

        <h1 className={styles.welcomeTitle}>Welcome to Bracha AI</h1>
        <p className={styles.loginSubtitle}>Sign in to continue</p>

        {/* Form Section */}
        <form onSubmit={handleSubmit} className={styles.form}>
          <div className={styles.inputGroup}>
            <label className={styles.inputLabel}>Email</label>
            <div className={styles.inputWithIcon}>
              <span className={styles.inputIcon}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="20" height="16" x="2" y="4" rx="2" />
                  <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
                </svg>
              </span>
              <input
                type="email"
                placeholder="your@email.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className={styles.textField}
                required
              />
            </div>
          </div>

          <div className={styles.inputGroup}>
            <label className={styles.inputLabel}>Password</label>
            <div className={styles.inputWithIcon}>
              <span className={styles.inputIcon}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect width="18" height="11" x="3" y="11" rx="2" ry="2" />
                  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                </svg>
              </span>
              <input
                type="password"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={styles.textField}
                required
              />
            </div>
          </div>

          <button type="submit" className={styles.loginButton}>
            Login
          </button>
        </form>

        <Link to="/signup" className={styles.signUpLink}>
          Create new account
        </Link>

        <div className={styles.footerNote}>
          Bracha AI analyzes your calls to help manage tasks and clients.
        </div>
      </div>
    </div>

  );
};

export default LoginPage;
