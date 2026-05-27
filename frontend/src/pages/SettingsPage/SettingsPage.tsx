import { useState } from 'react';
import BottomNav from '@/components/BottomNav';
import styles from './SettingsPage.module.css';

const SettingsPage: React.FC = () => {
    const [calendarSync, setCalendarSync] = useState(true);
    const [callRecording, setCallRecording] = useState(true);

    return (
        <div className={styles.pageWrapper}>
            <header className={styles.header}>
                <h1 className={styles.title}>Settings</h1>
            </header>

            <main className={styles.content}>
                {/* User Profile Section */}
                <section className={styles.profileSection}>
                    <div className={styles.profileInfo}>
                        <div className={styles.avatarBox}>
                            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
                                <circle cx="12" cy="7" r="4" />
                            </svg>
                        </div>
                        <div className={styles.userDetails}>
                            <h2 className={styles.userName}>David Cohen</h2>
                            <p className={styles.userEmail}>david@example.com</p>
                        </div>
                    </div>
                    <button className={styles.editBtn}>Edit</button>
                </section>

                {/* Integrations Section */}
                <section className={styles.settingsSection}>
                    <h3 className={styles.sectionTitle}>Integrations</h3>
                    <div className={styles.settingsCard}>
                        <div className={styles.settingItem}>
                            <div className={styles.settingInfo}>
                                <div className={`${styles.iconBox} ${styles.blueIcon}`}>
                                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                        <rect width="18" height="18" x="3" y="4" rx="2" ry="2" />
                                        <line x1="16" x2="16" y1="2" y2="6" />
                                        <line x1="8" x2="8" y1="2" y2="6" />
                                        <line x1="3" x2="21" y1="10" y2="10" />
                                    </svg>
                                </div>
                                <div className={styles.settingText}>
                                    <span className={styles.settingName}>Google Calendar Sync</span>
                                    <span className={styles.settingDescription}>Sync tasks with calendar</span>
                                </div>
                            </div>
                            <label className={styles.switch}>
                                <input
                                    type="checkbox"
                                    checked={calendarSync}
                                    onChange={() => setCalendarSync(!calendarSync)}
                                />
                                <span className={styles.slider}></span>
                            </label>
                        </div>
                    </div>
                </section>

                {/* Call Settings Section */}
                <section className={styles.settingsSection}>
                    <h3 className={styles.sectionTitle}>Call Settings</h3>
                    <div className={styles.settingsCard}>
                        <div className={styles.settingItem}>
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
                                    <span className={styles.settingDescription}>Record calls automatically</span>
                                </div>
                            </div>
                            <label className={styles.switch}>
                                <input
                                    type="checkbox"
                                    checked={callRecording}
                                    onChange={() => setCallRecording(!callRecording)}
                                />
                                <span className={styles.slider}></span>
                            </label>
                        </div>
                    </div>
                </section>

                {/* Privacy & Security Section */}
                <section className={styles.settingsSection}>
                    <h3 className={styles.sectionTitle}>Privacy & Security</h3>
                    <div className={styles.settingsCard}>
                        <button className={styles.menuItem}>
                            <div className={styles.settingInfo}>
                                <div className={`${styles.iconBox} ${styles.purpleIcon}`}>
                                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
                                    </svg>
                                </div>
                                <div className={styles.settingText}>
                                    <span className={styles.settingName}>Privacy & Permissions</span>
                                    <span className={styles.settingDescription}>Manage app permissions</span>
                                </div>
                            </div>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                <polyline points="9 18 15 12 9 6" />
                            </svg>
                        </button>
                    </div>
                </section>
            </main>

            <BottomNav />
        </div>
    );
};

export default SettingsPage;
