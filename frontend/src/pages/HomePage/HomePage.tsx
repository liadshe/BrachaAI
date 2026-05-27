import BottomNav from '@/components/BottomNav';
import styles from './HomePage.module.css';

const HomePage: React.FC = () => {
    return (
        <div className={styles.pageWrapper}>
            {/* Header */}
            <header className={styles.header}>
                <h1 className={styles.greeting}>Good Evening, David</h1>
            </header>

            <main className={styles.content}>
                {/* Search Bar */}
                <div className={styles.searchContainer}>
                    <div className={styles.searchBar}>
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.searchIcon}>
                            <circle cx="11" cy="11" r="8" />
                            <path d="m21 21-4.3-4.3" />
                        </svg>
                        <input type="text" placeholder="Search clients or calls..." className={styles.searchInput} />
                    </div>
                </div>

                {/* Overview Section */}
                <section className={styles.section}>
                    <h2 className={styles.sectionTitle}>Overview</h2>
                    <div className={styles.statsGrid}>
                        <div className={`${styles.statsCard} ${styles.blue}`}>
                            <div className={styles.statsIcon}>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <polyline points="9 11 12 14 22 4" />
                                    <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
                                </svg>
                            </div>
                            <span className={styles.statsNumber}>7</span>
                            <span className={styles.statsLabel}>Open Tasks</span>
                        </div>
                        <div className={`${styles.statsCard} ${styles.red}`}>
                            <div className={styles.statsIcon}>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <circle cx="12" cy="12" r="10" />
                                    <line x1="12" x2="12" y1="8" y2="12" />
                                    <line x1="12" x2="12.01" y1="16" y2="16" />
                                </svg>
                            </div>
                            <span className={styles.statsNumber}>2</span>
                            <span className={styles.statsLabel}>Overdue</span>
                        </div>
                        <div className={`${styles.statsCard} ${styles.green}`}>
                            <div className={styles.statsIcon}>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <polyline points="20 6 9 17 4 12" />
                                </svg>
                            </div>
                            <span className={styles.statsNumber}>5</span>
                            <span className={styles.statsLabel}>Closed Today</span>
                        </div>
                    </div>
                </section>

                {/* Recent Call Insights */}
                <section className={styles.section}>
                    <h2 className={styles.sectionTitle}>Recent Call Insights</h2>
                    <div className={styles.insightsList}>
                        <div className={styles.insightItem}>
                            <div className={styles.insightHeader}>
                                <div className={styles.callerInfo}>
                                    <div className={styles.callIconBox}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#2563eb" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="m3 21 1.9-1.9a2.36 2.36 0 0 0 0-3.3l-1.44-1.44a2.36 2.36 0 0 1 0-3.32l.83-.83a2.36 2.36 0 0 1 3.32 0l1.44 1.44a2.36 2.36 0 0 0 3.3 0L14.5 9.5a2.36 2.36 0 0 0 0-3.3l-1.44-1.44a2.36 2.36 0 0 1 0-3.32l.83-.83a2.36 2.36 0 0 1 3.32 0l1.44 1.44a2.36 2.36 0 0 0 3.3 0l1.9 1.9" />
                                        </svg>
                                    </div>
                                    <span className={styles.callerName}>Michael Cohen</span>
                                </div>
                                <span className={styles.callTime}>10:30 AM</span>
                            </div>
                            <p className={styles.insightSummary}>
                                Discussed new project scope and timeline expectations for Q1 deliverables
                            </p>
                            <div className={styles.badge}>Processed</div>
                        </div>
                    </div>
                </section>
            </main>

            <BottomNav />
        </div>
    );
};

export default HomePage;
