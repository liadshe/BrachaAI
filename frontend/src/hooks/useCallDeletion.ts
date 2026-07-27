import { useState } from 'react';
import apiClient from '@/services/apiClient';

export interface CallDeletionOptions {
    /** Called on success with the ids that were removed, so the page can drop them from its list. */
    onDeleted: (deletedIds: Set<string>) => void;
    /** Called on failure to resync the page's list with what the server actually has. */
    onRefetch: () => Promise<void>;
}

export interface CallDeletion {
    isDeleting: boolean;
    error: string | null;
    deleteCalls: (ids: string[]) => Promise<boolean>;
}

export const useCallDeletion = ({ onDeleted, onRefetch }: CallDeletionOptions): CallDeletion => {
    const [isDeleting, setIsDeleting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const deleteCalls = async (ids: string[]): Promise<boolean> => {
        setIsDeleting(true);
        setError(null);

        try {
            const response = await apiClient.post('/calls/bulk-delete', { ids });

            if (response.data?.deletedCount !== ids.length) {
                // A short count means some ids were not ours to delete. Don't
                // pretend it worked — fall through to the resync below.
                throw new Error('count mismatch');
            }

            onDeleted(new Set(ids));
            return true;
        } catch (err) {
            console.error('Error deleting calls:', err);
            setError('Could not delete those calls. Please try again.');

            try {
                await onRefetch();
            } catch (refetchError) {
                console.error('Error refreshing calls:', refetchError);
            }

            return false;
        } finally {
            setIsDeleting(false);
        }
    };

    return { isDeleting, error, deleteCalls };
};
