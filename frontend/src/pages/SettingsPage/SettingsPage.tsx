import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { endSession } from '@/services/session';
import { getStoredUser } from '@/services/authTokens';
import BottomNav from '@/components/BottomNav';
import styles from './SettingsPage.module.css';

const SettingsPage: React.FC = () => {
    const navigate = useNavigate();
    const [user, setUser] = useState<any>(getStoredUser());
    const [callRecordingSupported, setCallRecordingSupported] = useState(false);
    const [deleteAudioAfterProcessing, setDeleteAudioAfterProcessing] = useState(true);
    const [audioSettingSupported, setAudioSettingSupported] = useState(false);

    useEffect(() => {
        const storedUser = getStoredUser();
        setUser(storedUser);

        // Native-only setting: the Android host owns it so the background call-processing
        // service can read it offline and before login. Absent in a plain browser, where
        // there are no device recordings to delete.
        const bridge = window.BrachaNative;
        if (bridge?.getDeleteAudioAfterProcessing && bridge.setDeleteAudioAfterProcessing) {
            setAudioSettingSupported(true);
            setDeleteAudioAfterProcessing(bridge.getDeleteAudioAfterProcessing());
        }

        // The app cannot record calls; the phone's dialer does, into the directory the
        // native service watches. So this row only shortcuts to the dialer's setting, and in
        // a browser there is nothing to shortcut to.
        if (bridge?.openCallRecordingSettings) {
            setCallRecordingSupported(true);
        }
    }, []);

    // Deliberately not routed through handleToggle: this setting lives in Android
    // SharedPreferences, not in the backend user record, so it must not PUT /auth/profile.
    const handleDeleteAudioToggle = (value: boolean) => {
        setDeleteAudioAfterProcessing(value);
        window.BrachaNative?.setDeleteAudioAfterProcessing?.(value);
    };

    const formatPhoneNumber = (phone: string) => {
        if (!phone) return '';
        const digits = phone.replace(/\D/g, '');
        if (digits.length !== 10) return phone; // fallback if not a standard 10-digit number
        return `${digits.slice(0, 3)}-${digits.slice(3)}`;
    };

    const getInitials = (fullName: string) => {
        if (!fullName) return '?';
        return fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2);
    };

    const isGradientAvatar = user.profilePicture?.startsWith('linear-gradient') || user.profilePicture?.startsWith('#');

    return (
        <div className={styles.pageWrapper}>
            <div className={styles.contentArea}>
                <header className={styles.header}>
                    <h1 className={styles.title}>Settings</h1>
                </header>

                <main className={styles.content}>
                {/* User Profile Section */}
                <section className={styles.profileSection}>
                    <div className={styles.profileInfo}>
                        {isGradientAvatar ? (
                            <div className={styles.avatarBox} style={{ background: user.profilePicture }}>
                                <span style={{ color: 'white', fontWeight: 'bold', fontSize: '20px', letterSpacing: '0.05em' }}>
                                    {getInitials(user.name)}
                                </span>
                            </div>
                        ) : (
                            <div className={styles.avatarBox}>
                                {user.profilePicture ? (
                                    <img src={user.profilePicture} alt="Profile" className={styles.avatarImg} />
                                ) : (
                                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
                                        <circle cx="12" cy="7" r="4" />
                                    </svg>
                                )}
                            </div>
                        )}
                        <div className={styles.userDetails}>
                            <h2 className={styles.userName}>{user.name || 'David Cohen'}</h2>
                            <p className={styles.userEmail}>{user.phoneNumber ? formatPhoneNumber(user.phoneNumber) : 'No phone number'}</p>
                            {user.businessDescription ? (
                                <p className={styles.userBusinessDescription}>{user.businessDescription}</p>
                            ) : null}
                        </div>
                    </div>
                    <button className={styles.editBtn} onClick={() => navigate('/edit-profile')}>Edit</button>
                </section>

                {/* Call Settings Section */}
                <section className={styles.settingsSection}>
                    <h3 className={styles.sectionTitle}>Call Settings</h3>
                    <div className={styles.settingsCard}>
                        {callRecordingSupported && (
                            <button
                                className={`${styles.settingItem} ${styles.settingButton}`}
                                onClick={() => window.BrachaNative?.openCallRecordingSettings?.()}
                            >
                                <div className={styles.settingInfo}>
                                    <div className={`${styles.iconBox} ${styles.redIcon}`}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z" />
                                            <path d="M19 10v1a7 7 0 0 1-14 0v-1" />
                                            <line x1="12" x2="12" y1="19" y2="22" />
                                        </svg>
                                    </div>
                                    <div className={styles.settingText}>
                                        <span className={styles.settingName}>Automatic Call Recording</span>
                                        <span className={styles.settingDescription}>Enable it in your Phone app settings</span>
                                    </div>
                                </div>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <polyline points="9 18 15 12 9 6" />
                                </svg>
                            </button>
                        )}
                        {audioSettingSupported && (
                            <div className={styles.settingItem}>
                                <div className={styles.settingInfo}>
                                    <div className={`${styles.iconBox} ${styles.amberIcon}`}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="M3 6h18" />
                                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
                                            <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                                            <line x1="10" x2="10" y1="11" y2="17" />
                                            <line x1="14" x2="14" y1="11" y2="17" />
                                        </svg>
                                    </div>
                                    <div className={styles.settingText}>
                                        <span className={styles.settingName}>Delete Audio After Processing</span>
                                        <span className={styles.settingDescription}>Free up storage by removing recordings once transcribed</span>
                                    </div>
                                </div>
                                <label className={styles.switch}>
                                    <input
                                        type="checkbox"
                                        checked={deleteAudioAfterProcessing}
                                        onChange={(e) => handleDeleteAudioToggle(e.target.checked)}
                                    />
                                    <span className={styles.slider}></span>
                                </label>
                            </div>
                        )}
                    </div>
                </section>

                {/* Account Section */}
                <section className={styles.settingsSection}>
                    <h3 className={styles.sectionTitle}>Account</h3>
                    <div className={styles.settingsCard}>
                        <button className={styles.menuItem} onClick={async () => {
                            await endSession();
                            navigate('/login');
                        }}>
                            <div className={styles.settingInfo}>
                                <div className={`${styles.iconBox} ${styles.redIcon}`}>
                                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                                        <polyline points="16 17 21 12 16 7" />
                                        <line x1="21" x2="9" y1="12" y2="12" />
                                    </svg>
                                </div>
                                <div className={styles.settingText}>
                                    <span className={styles.settingName} style={{ color: '#e11d48' }}>Log Out</span>
                                    <span className={styles.settingDescription}>Sign out of your account</span>
                                </div>
                            </div>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#e11d48" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                <polyline points="9 18 15 12 9 6" />
                            </svg>
                        </button>
                    </div>
                </section>
            </main>
            </div>
            <BottomNav />
        </div>
    );
};

export default SettingsPage;