import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import apiClient from '../../services/apiClient';
import BottomNav from '@/components/BottomNav';
import styles from './ContactsPage.module.css';

interface Contact {
    _id: string;
    name: string;
    phone: string;
    email?: string;
    lastNote?: string;
    initials?: string;
}

const ContactsPage: React.FC = () => {
    const navigate = useNavigate();
    const [searchQuery, setSearchQuery] = useState('');
    const [contacts, setContacts] = useState<Contact[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchContacts = async () => {
            try {
                const response = await apiClient.get('/contacts');
                setContacts(response.data);
            } catch (error) {
                console.error('Error fetching contacts:', error);
            } finally {
                setLoading(false);
            }
        };
        fetchContacts();
    }, []);

    const filteredContacts = contacts.filter(contact =>
        contact.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (contact.phone && contact.phone.includes(searchQuery))
    );

    return (
        <div className={styles.pageWrapper}>
            <header className={styles.header}>
                <h1 className={styles.title}>Contacts</h1>
            </header>

            <div className={styles.searchContainer}>
                <div className={styles.searchBar}>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.searchIcon}>
                        <circle cx="11" cy="11" r="8" />
                        <path d="m21 21-4.3-4.3" />
                    </svg>
                    <input
                        type="text"
                        placeholder="Search contacts..."
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        className={styles.searchInput}
                    />
                </div>
            </div>

            <main className={styles.clientList}>
                {loading ? (
                    <p className={styles.statusMessage}>Loading contacts...</p>
                ) : filteredContacts.length > 0 ? (
                    filteredContacts.map(contact => (
                        <div 
                            key={contact._id} 
                            className={styles.clientCard} 
                            onClick={() => navigate(`/contacts/${contact._id}`)}
                            style={{ cursor: 'pointer' }}
                        >
                            <div className={styles.avatar}>
                                {contact.initials || contact.name.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)}
                            </div>
                            <div className={styles.clientInfo}>
                                <div className={styles.clientHeader}>
                                    <h3 className={styles.clientName}>{contact.name}</h3>
                                </div>
                                <div className={styles.phoneBox}>
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                                    </svg>
                                    <span className={styles.phoneNumber}>{contact.phone}</span>
                                </div>
                                <p className={styles.lastNote}>{contact.lastNote || 'No recent notes'}</p>
                            </div>
                        </div>
                    ))
                ) : (
                    <p className={styles.statusMessage}>No contacts found.</p>
                )}
            </main>

            <BottomNav />
        </div>
    );
};

export default ContactsPage;
