import styles from './ConfirmDialog.module.css';

interface ConfirmDialogProps {
    title: string;
    message: string;
    confirmLabel?: string;
    busy?: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}

const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
    title,
    message,
    confirmLabel = 'Delete',
    busy = false,
    onCancel,
    onConfirm,
}) => (
    <div className={styles.overlay}>
        <div className={styles.content} role="alertdialog" aria-modal="true">
            <h2 className={styles.title}>{title}</h2>
            <p className={styles.message}>{message}</p>
            <div className={styles.actions}>
                <button type="button" className={styles.cancelBtn} onClick={onCancel} disabled={busy}>
                    Cancel
                </button>
                <button type="button" className={styles.confirmBtn} onClick={onConfirm} disabled={busy}>
                    {busy ? 'Deleting…' : confirmLabel}
                </button>
            </div>
        </div>
    </div>
);

export default ConfirmDialog;
