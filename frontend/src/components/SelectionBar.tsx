import styles from './SelectionBar.module.css';

interface SelectionBarProps {
    count: number;
    onCancel: () => void;
    onDelete: () => void;
}

const SelectionBar: React.FC<SelectionBarProps> = ({ count, onCancel, onDelete }) => (
    <div className={styles.bar}>
        <button type="button" className={styles.iconBtn} onClick={onCancel} aria-label="Cancel selection">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
        </button>

        <span className={styles.count}>{count} selected</span>

        <button type="button" className={styles.iconBtn} onClick={onDelete} aria-label="Delete selected">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
            </svg>
        </button>
    </div>
);

export default SelectionBar;
