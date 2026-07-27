import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('@/services/apiClient', () => ({
    default: { post: vi.fn(), get: vi.fn() },
}));

import apiClient from '@/services/apiClient';
import { useCallDeletion } from './useCallDeletion';

const IDS = ['call-a', 'call-b'];

const setup = () => {
    const onDeleted = vi.fn();
    const onRefetch = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useCallDeletion({ onDeleted, onRefetch }));
    return { result, onDeleted, onRefetch };
};

describe('useCallDeletion', () => {
    beforeEach(() => {
        vi.mocked(apiClient.post).mockReset();
    });

    it('posts the ids to the bulk-delete endpoint', async () => {
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        const { result } = setup();

        await act(async () => {
            await result.current.deleteCalls(IDS);
        });

        expect(apiClient.post).toHaveBeenCalledWith('/calls/bulk-delete', { ids: IDS });
    });

    it('reports success and hands back the deleted ids', async () => {
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(true);
        expect(onDeleted).toHaveBeenCalledWith(new Set(IDS));
        expect(onRefetch).not.toHaveBeenCalled();
        expect(result.current.error).toBeNull();
    });

    it('treats a short deletedCount as a failure and resyncs', async () => {
        // A short count means some ids were not ours to delete. Reporting
        // success here would leave the UI claiming rows are gone that aren't.
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 1 } } as any);
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(false);
        expect(onDeleted).not.toHaveBeenCalled();
        expect(onRefetch).toHaveBeenCalledTimes(1);
        expect(result.current.error).not.toBeNull();
    });

    it('reports failure and resyncs when the request throws', async () => {
        vi.mocked(apiClient.post).mockRejectedValue(new Error('network down'));
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(false);
        expect(onDeleted).not.toHaveBeenCalled();
        expect(onRefetch).toHaveBeenCalledTimes(1);
        expect(result.current.error).not.toBeNull();
    });

    it('survives a refetch that also fails', async () => {
        vi.mocked(apiClient.post).mockRejectedValue(new Error('network down'));
        const onDeleted = vi.fn();
        const onRefetch = vi.fn().mockRejectedValue(new Error('still down'));
        const { result } = renderHook(() => useCallDeletion({ onDeleted, onRefetch }));

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(false);
        expect(result.current.error).not.toBeNull();
    });

    it('clears a previous error when a later delete succeeds', async () => {
        const { result } = setup();

        vi.mocked(apiClient.post).mockRejectedValue(new Error('network down'));
        await act(async () => {
            await result.current.deleteCalls(IDS);
        });
        expect(result.current.error).not.toBeNull();

        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        await act(async () => {
            await result.current.deleteCalls(IDS);
        });

        expect(result.current.error).toBeNull();
    });

    it('does not send a request when called with an empty id list', async () => {
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls([]);
        });

        expect(apiClient.post).not.toHaveBeenCalled();
        expect(onDeleted).not.toHaveBeenCalled();
        expect(onRefetch).not.toHaveBeenCalled();
        expect(outcome).toBe(false);
        expect(result.current.error).toBeNull();
    });

    it('is not deleting once the request settles', async () => {
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        const { result } = setup();

        await act(async () => {
            await result.current.deleteCalls(IDS);
        });

        expect(result.current.isDeleting).toBe(false);
    });
});
