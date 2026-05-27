import { useState } from 'react';
import BottomNav from '@/components/BottomNav';
import styles from './ClientsPage.module.css';

interface Client {
    id: string;
    name: string;
    phone: string;
    tasks: number;
    lastNote: string;
    initials: string;
}

const ClientsPage: React.FC = () => {
    const [searchQuery, setSearchQuery] = useState('');
    const [clients] = useState<Client[]>([
        { id: '1', name: 'David Cohen', phone: '+972-54-123-4567', tasks: 2, lastNote: 'Discussed new project scope', initials: 'DC' },
        { id: '2', name: 'Sarah Levi', phone: '+972-52-987-6543', tasks: 1, lastNote: 'Contract renewal discussion', initials: 'SL' },
        { id: '3', name: 'Michael Shapiro', phone: '+972-50-555-1234', tasks: 3, lastNote: 'Payment terms agreement', initials: 'MS' },
        { id: '4', name: 'Rachel Cohen', phone: '+972-54-777-8888', tasks: 0, lastNote: 'Initial consultation completed', initials: 'RC' },
    ]);

    const filteredClients = clients.filter(client =>
        client.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        client.phone.includes(searchQuery)
    );

    return (
        <div className={styles.pageWrapper}>
            <header className={styles.header}>
                <h1 className={styles.title}>Clients</h1>
                <div className={styles.searchContainer}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.searchIcon}>
                        <circle cx="11" cy="11" r="8" />
                        <path d="m21 21-4.3-4.3" />
                    </svg>
                    <input
                        type="text"
                        placeholder="Search clients..."
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        className={styles.searchInput}
                    />
                </div>
            </header>

            <main className={styles.clientList}>
                {filteredClients.map(client => (
                    <div key={client.id} className={styles.clientCard}>
                        <div className={styles.avatar}>
                            {client.initials}
                        </div>
                        <div className={styles.clientInfo}>
                            <div className={styles.clientHeader}>
                                <h3 className={styles.clientName}>{client.name}</h3>
                                {client.tasks > 0 && (
                                    <span className={styles.taskBadge}>{client.tasks} {client.tasks === 1 ? 'task' : 'tasks'}</span>
                                )}
                            </div>
                            <div className={styles.phoneBox}>
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                                </svg>
                                <span className={styles.phoneNumber}>{client.phone}</span>
                            </div>
                            <p className={styles.lastNote}>{client.lastNote}</p>
                        </div>
                    </div>
                ))}
            </main>

            <BottomNav />
        </div>
    );
};

export default ClientsPage;
