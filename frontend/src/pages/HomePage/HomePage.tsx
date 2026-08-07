import { useCallback, useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import apiClient from '../../services/apiClient';
import BottomNav from '@/components/BottomNav';
import SelectionBar from '@/components/SelectionBar';
import ConfirmDialog from '@/components/ConfirmDialog';
import { useMultiSelect } from '@/hooks/useMultiSelect';
import { useSelectionBackButton } from '@/hooks/useSelectionBackButton';
import { useCallDeletion } from '@/hooks/useCallDeletion';
import { getStoredUser } from '@/services/authTokens';
import { formatDuration } from '@/utils/formatDuration';
import styles from './HomePage.module.css';

const HomePage: React.FC = () => {
    const [calls, setCalls] = useState<any[]>([]);
    const [stats, setStats] = useState({ open: 0, overdue: 0, closedToday: 0 });
    const [loading, setLoading] = useState(true);
    const [searchQuery, setSearchQuery] = useState('');
    const user = getStoredUser();
    const navigate = useNavigate();

    const callSelection = useMultiSelect();
    const [isDeleteOpen, setIsDeleteOpen] = useState(false);

    // Back must also close the confirm dialog — otherwise the selection
    // clears but the dialog stays open, now reading "Delete 0 calls?" with
    // an enabled Delete button that would POST an empty id list.
    const exitSelection = useCallback(() => {
        setIsDeleteOpen(false);
        callSelection.clear();
    }, [callSelection.clear]);

    useSelectionBackButton(callSelection.isSelecting, exitSelection);

    const callDeletion = useCallDeletion({
        onDeleted: (deletedIds) => {
            setCalls(prev => prev.filter(call => !deletedIds.has(call._id)));
        },
        onRefetch: async () => {
            const callsRes = await apiClient.get('/calls');
            setCalls(callsRes.data);
        },
    });

    useEffect(() => {
        const fetchData = async () => {
            try {
                const [callsRes, statsRes] = await Promise.all([
                    apiClient.get('/calls'),
                    apiClient.get('/tasks/summary')
                ]);

                setCalls(callsRes.data);
                setStats(statsRes.data);
            } catch (error) {
                console.error('Error fetching dashboard data:', error);
            } finally {
                setLoading(false);
            }
        };

        fetchData();
    }, []);

    const getGreeting = () => {
        const hour = new Date().getHours();
        if (hour < 12) return 'Good Morning';
        if (hour < 18) return 'Good Afternoon';
        return 'Good Evening';
    };

    const filteredCalls = calls.filter(call => {
        const contactName = (call.contactId?.name || call.callerName || '').toLowerCase();
        const summary = (call.callSummary || '').toLowerCase();
        const query = searchQuery.toLowerCase();
        return contactName.includes(query) || summary.includes(query);
    });

    const handleDeleteSelectedCalls = async () => {
        await callDeletion.deleteCalls(callSelection.selectedIds);
        callSelection.clear();
        setIsDeleteOpen(false);
    };

    /**
     * Opens the call where it lives — the Calls tab of its contact, scrolled to and
     * highlighting this specific call.
     *
     * Passed as getItemProps' onActivate rather than as an onClick of our own, so the
     * hook keeps deciding what a tap means: long-press still selects, and a tap while
     * a selection is open still toggles instead of navigating away from it.
     *
     * Every call has a contact — Call.contactId is required and the upload path resolves
     * one before saving — so the guard is for an unpopulated read, not a real state.
     */
    const openCall = (call: any) => {
        const contactId = call.contactId?._id;
        if (!contactId) return;
        navigate(`/contacts/${contactId}?call=${call._id}`);
    };

    return (
        <div className={styles.pageWrapper}>
            {callSelection.isSelecting && (
                <>
                    <SelectionBar
                        count={callSelection.count}
                        onCancel={callSelection.clear}
                        onDelete={() => setIsDeleteOpen(true)}
                    />
                    <div className={styles.selectionSpacer} />
                </>
            )}

            <div className={styles.contentArea}>
                <header className={styles.header}>
                    <h1 className={styles.greeting}>{getGreeting()}, {user.name || 'User'}</h1>
                </header>

                <main className={styles.content}>
                    <div className={styles.searchContainer}>
                        <div className={styles.searchBar}>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.searchIcon}>
                                <circle cx="11" cy="11" r="8" />
                                <path d="m21 21-4.3-4.3" />
                            </svg>
                            <input
                                type="text"
                                placeholder="Search contacts or calls..."
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                className={styles.searchInput}
                                disabled={loading}
                            />
                        </div>
                    </div>

                    <section className={styles.section}>
                        <h2 className={styles.sectionTitle}>Overview</h2>
                        {loading ? (
                            <div className={styles.statsGrid}>
                                {Array.from({ length: 3 }).map((_, index) => (
                                    <div key={index} className={`${styles.statsCard} ${styles.placeholderCard}`}>
                                        <div className={styles.placeholderLine} style={{ width: '50%', height: '18px' }} />
                                        <div className={styles.placeholderLine} style={{ width: '40%', height: '28px' }} />
                                        <div className={styles.placeholderLine} style={{ width: '60%', height: '14px' }} />
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <div className={styles.statsGrid}>
                                <div className={`${styles.statsCard} ${styles.blue}`} onClick={() => navigate('/tasks')}>
                                    <div className={styles.statsIcon}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <polyline points="9 11 12 14 22 4" />
                                            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
                                        </svg>
                                    </div>
                                    <span className={styles.statsNumber}>{stats.open}</span>
                                    <span className={styles.statsLabel}>Open Tasks</span>
                                </div>
                                <div className={`${styles.statsCard} ${styles.red}`} onClick={() => navigate('/tasks?filter=Overdue')}>
                                    <div className={styles.statsIcon}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <circle cx="12" cy="12" r="10" />
                                            <line x1="12" x2="12" y1="8" y2="12" />
                                            <line x1="12" x2="12.01" y1="16" y2="16" />
                                        </svg>
                                    </div>
                                    <span className={styles.statsNumber}>{stats.overdue}</span>
                                    <span className={styles.statsLabel}>Overdue</span>
                                </div>
                                <div className={`${styles.statsCard} ${styles.green}`} onClick={() => navigate('/tasks?filter=Done')}>
                                    <div className={styles.statsIcon}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <polyline points="20 6 9 17 4 12" />
                                        </svg>
                                    </div>
                                    <span className={styles.statsNumber}>{stats.closedToday}</span>
                                    <span className={styles.statsLabel}>Closed Today</span>
                                </div>
                            </div>
                        )}
                    </section>

                    <section className={styles.section}>
                        <h2 className={styles.sectionTitle}>Recent Call Insights</h2>
                        {callDeletion.error && <p className={styles.statusMessage}>{callDeletion.error}</p>}
                        <div className={styles.insightsList}>
                            {loading ? (
                                <div className={styles.loadingPlaceholder}>
                                    {Array.from({ length: 3 }).map((_, index) => (
                                        <div key={index} className={styles.placeholderCard}>
                                            <div className={styles.placeholderLine} style={{ width: '70%' }} />
                                            <div className={styles.placeholderLine} style={{ width: '90%' }} />
                                            <div className={styles.placeholderLine} style={{ width: '55%' }} />
                                        </div>
                                    ))}
                                </div>
                            ) : filteredCalls.length > 0 ? (
                                filteredCalls.map((call) => (
                                    <div
                                        key={call._id}
                                        className={`${styles.insightItem} ${styles.selectableCard} ${callSelection.isSelected(call._id) ? styles.selectedCard : ''}`}
                                        {...callSelection.getItemProps(call._id, () => openCall(call))}
                                    >
                                        <div className={styles.insightHeader}>
                                            <div className={styles.callerInfo}>
                                                <div className={styles.callIconBox}>
                                                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#2563eb" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                                        <path d="m3 21 1.9-1.9a2.36 2.36 0 0 0 0-3.3l-1.44-1.44a2.36 2.36 0 0 1 0-3.32l.83-.83a2.36 2.36 0 0 1 3.32 0l1.44 1.44a2.36 2.36 0 0 0 3.3 0L14.5 9.5a2.36 2.36 0 0 0 0-3.3l-1.44-1.44a2.36 2.36 0 0 1 0-3.32l.83-.83a2.36 2.36 0 0 1 3.32 0l1.44 1.44a2.36 2.36 0 0 0 3.3 0l1.9 1.9" />
                                                    </svg>
                                                </div>
                                                <span className={styles.callerName}>
                                                    {call.contactId?.name || call.callerName || 'Unknown Caller'}
                                                </span>
                                            </div>
                                            <span className={styles.callTime}>
                                                {call.callDateTime ? new Date(call.callDateTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''}
                                                {formatDuration(call.callLength) ? ` • ${formatDuration(call.callLength)}` : ''}
                                            </span>
                                        </div>
                                        <p className={styles.insightSummary}>
                                            {call.callSummary || 'Summary pending analysis...'}
                                        </p>
                                        <div className={styles.badge}>{call.status || 'Processed'}</div>
                                    </div>
                                ))
                            ) : (
                                <p className={styles.statusMessage}>No recent calls found.</p>
                            )}
                        </div>
                    </section>
                </main>
            </div>

            {isDeleteOpen && (
                <ConfirmDialog
                    title={`Delete ${callSelection.count} ${callSelection.count === 1 ? 'call' : 'calls'}?`}
                    message="This can't be undone."
                    busy={callDeletion.isDeleting}
                    onCancel={() => setIsDeleteOpen(false)}
                    onConfirm={handleDeleteSelectedCalls}
                />
            )}

            <BottomNav />
        </div>
    );
};

export default HomePage;
