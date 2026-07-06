import { useState, FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import styles from './SignupPage.module.css';

const baseURL = (import.meta.env.VITE_API_URL as string);

const SignupPage: React.FC = () => {
    const [fullName, setFullName] = useState('');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [phoneNumber, setPhoneNumber] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [error, setError] = useState('');
    const navigate = useNavigate();

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setError('');

        if (password !== confirmPassword) {
            setError('Passwords do not match');
            return;
        }

        try {
            const response = await axios.post(baseURL + '/auth/signup', {
                name: fullName,
                email,
                phoneNumber,
                password
            });

            const { token, user } = response.data;
            localStorage.setItem('token', token);
            localStorage.setItem('user', JSON.stringify(user));

            navigate('/home');
        } catch (err: any) {
            console.error('Signup error:', err);
            setError(err.response?.data?.message || 'An error occurred during signup');
        }
    };


    return (
        <div className={styles.pageWrapper}>
            <div className={styles.card}>
                <Link to="/login" className={styles.backLink}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="m15 18-6-6 6-6" />
                    </svg>
                    <span>Back to Login</span>
                </Link>

                {/* Logo Section */}
                <div className={styles.logoSection}>
                <img src="/logo.png" alt="Bracha AI" className={styles.logoImage} />
                </div>
                <h1 className={styles.title}>Create Account</h1>
                <p className={styles.subtitle}>Join Bracha AI to manage your business</p>

                {error && <div className={styles.errorMessage}>{error}</div>}

                {/* Form Section */}

                <form onSubmit={handleSubmit} className={styles.form}>
                    <div className={styles.inputGroup}>
                        <label className={styles.inputLabel}>Full Name</label>
                        <div className={styles.inputWithIcon}>
                            <span className={styles.inputIcon}>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
                                    <circle cx="12" cy="7" r="4" />
                                </svg>
                            </span>
                            <input
                                type="text"
                                placeholder="Your name"
                                value={fullName}
                                onChange={(e) => setFullName(e.target.value)}
                                className={styles.textField}
                                required
                            />
                        </div>
                    </div>

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
                        <label className={styles.inputLabel}>Phone Number</label>
                        <div className={styles.inputWithIcon}>
                            <span className={styles.inputIcon}>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                                </svg>
                            </span>
                            <input
                                type="tel"
                                placeholder="Your phone number"
                                value={phoneNumber}
                                onChange={(e) => setPhoneNumber(e.target.value)}
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

                    <div className={styles.inputGroup}>
                        <label className={styles.inputLabel}>Confirm Password</label>
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
                                value={confirmPassword}
                                onChange={(e) => setConfirmPassword(e.target.value)}
                                className={styles.textField}
                                required
                            />
                        </div>
                    </div>

                    <button type="submit" className={styles.signupButton}>
                        Create Account
                    </button>
                </form>
            </div>
        </div>
    );
};

export default SignupPage;
