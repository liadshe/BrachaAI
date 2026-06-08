import { useState, FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import apiClient from '../../services/apiClient';
import styles from './EditProfilePage.module.css';

const PRESET_AVATARS = [
    { name: 'Blue', color: '#3b82f6', bg: 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)' },
    { name: 'Purple', color: '#a855f7', bg: 'linear-gradient(135deg, #a855f7 0%, #6b21a8 100%)' },
    { name: 'Orange', color: '#f97316', bg: 'linear-gradient(135deg, #f97316 0%, #c2410c 100%)' },
    { name: 'Green', color: '#10b981', bg: 'linear-gradient(135deg, #10b981 0%, #047857 100%)' },
    { name: 'Red', color: '#ef4444', bg: 'linear-gradient(135deg, #ef4444 0%, #b91c1c 100%)' },
];

const EditProfilePage: React.FC = () => {
    const navigate = useNavigate();
    const [user, setUser] = useState<any>(JSON.parse(localStorage.getItem('user') || '{}'));
    
    const [name, setName] = useState(user.name || '');
    const [email, setEmail] = useState(user.email || '');
    const [password, setPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [selectedAvatar, setSelectedAvatar] = useState(user.profilePicture || PRESET_AVATARS[0].bg);
    
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        setError('');
        setSuccess('');

        if (password && password !== confirmPassword) {
            setError('Passwords do not match');
            return;
        }

        setLoading(true);
        try {
            const response = await apiClient.put('/auth/profile', {
                name,
                email,
                password: password || undefined,
                profilePicture: selectedAvatar
            });

            const updatedUser = response.data.user;
            localStorage.setItem('user', JSON.stringify(updatedUser));
            setUser(updatedUser);
            
            setSuccess('Profile updated successfully!');
            setTimeout(() => {
                navigate('/settings');
            }, 1200);
        } catch (err: any) {
            console.error('Update profile error:', err);
            setError(err.response?.data?.message || 'Failed to update profile');
        } finally {
            setLoading(false);
        }
    };

    const getInitials = (fullName: string) => {
        if (!fullName) return '?';
        return fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2);
    };

    return (
        <div className={styles.pageWrapper}>
            <div className={styles.card}>
                <Link to="/settings" className={styles.backLink}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="m15 18-6-6 6-6" />
                    </svg>
                    <span>Back to Settings</span>
                </Link>

                <h1 className={styles.title}>Edit Profile</h1>
                <p className={styles.subtitle}>Update your account information</p>

                {/* Avatar theme selector at top of card */}
                <div className={styles.avatarSection}>
                    <div 
                        className={styles.avatarPreview} 
                        style={{ background: selectedAvatar }}
                    >
                        <span className={styles.avatarInitials}>{getInitials(name)}</span>
                    </div>
                    <p className={styles.avatarLabel}>Choose Avatar Theme</p>
                    <div className={styles.avatarGrid}>
                        {PRESET_AVATARS.map((avatar) => (
                            <button
                                key={avatar.name}
                                type="button"
                                className={`${styles.avatarOption} ${selectedAvatar === avatar.bg ? styles.activeAvatar : ''}`}
                                style={{ background: avatar.bg }}
                                onClick={() => setSelectedAvatar(avatar.bg)}
                                aria-label={`Select ${avatar.name} avatar theme`}
                            />
                        ))}
                    </div>
                </div>

                {error && <div className={styles.errorMessage}>{error}</div>}
                {success && <div className={styles.successMessage}>{success}</div>}

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
                                value={name}
                                onChange={(e) => setName(e.target.value)}
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

                    <div className={styles.divider}>
                        <span>Change Password (Optional)</span>
                    </div>

                    <div className={styles.inputGroup}>
                        <label className={styles.inputLabel}>New Password</label>
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
                            />
                        </div>
                    </div>

                    <div className={styles.inputGroup}>
                        <label className={styles.inputLabel}>Confirm New Password</label>
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
                            />
                        </div>
                    </div>

                    <button type="submit" className={styles.submitButton} disabled={loading}>
                        {loading ? 'Saving Changes...' : 'Save Profile'}
                    </button>
                </form>
            </div>
        </div>
    );
};

export default EditProfilePage;
