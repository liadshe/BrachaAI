import { useCallback, useRef, useState } from 'react';

export const LONG_PRESS_MS = 500;
export const MOVE_CANCEL_PX = 10;

/** The subset of a PointerEvent this hook reads. */
export interface PressLikeEvent {
    clientX: number;
    clientY: number;
}

export interface ItemPointerProps {
    onPointerDown: (event: PressLikeEvent) => void;
    onPointerMove: (event: PressLikeEvent) => void;
    onPointerUp: () => void;
    onPointerCancel: () => void;
    onClick: () => void;
}

export interface MultiSelect {
    isSelecting: boolean;
    selectedIds: string[];
    count: number;
    isSelected: (id: string) => boolean;
    toggle: (id: string) => void;
    clear: () => void;
    getItemProps: (id: string, onActivate?: () => void) => ItemPointerProps;
}

export const useMultiSelect = (): MultiSelect => {
    const [selected, setSelected] = useState<Set<string>>(() => new Set());

    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const startRef = useRef<PressLikeEvent | null>(null);
    const suppressClickRef = useRef(false);

    const cancelPendingPress = useCallback(() => {
        if (timerRef.current !== null) {
            clearTimeout(timerRef.current);
            timerRef.current = null;
        }
        startRef.current = null;
    }, []);

    const toggle = useCallback((id: string) => {
        setSelected(prev => {
            const next = new Set(prev);
            if (next.has(id)) {
                next.delete(id);
            } else {
                next.add(id);
            }
            return next;
        });
    }, []);

    const clear = useCallback(() => setSelected(new Set()), []);

    // Deliberately not memoized: onClick has to read the current `selected`
    // set, and a stale memoized closure would activate rows instead of
    // toggling them.
    const getItemProps = (id: string, onActivate?: () => void): ItemPointerProps => ({
        onPointerDown: (event: PressLikeEvent) => {
            cancelPendingPress();
            startRef.current = { clientX: event.clientX, clientY: event.clientY };
            timerRef.current = setTimeout(() => {
                timerRef.current = null;
                startRef.current = null;
                // A click always follows the pointerup that ends a long press.
                // Without this flag it would immediately undo the selection.
                suppressClickRef.current = true;
                setSelected(prev => {
                    const next = new Set(prev);
                    next.add(id);
                    return next;
                });
            }, LONG_PRESS_MS);
        },

        onPointerMove: (event: PressLikeEvent) => {
            const start = startRef.current;
            if (!start || timerRef.current === null) return;
            const dx = event.clientX - start.clientX;
            const dy = event.clientY - start.clientY;
            if (Math.sqrt(dx * dx + dy * dy) > MOVE_CANCEL_PX) {
                cancelPendingPress();
            }
        },

        onPointerUp: cancelPendingPress,
        onPointerCancel: cancelPendingPress,

        onClick: () => {
            if (suppressClickRef.current) {
                suppressClickRef.current = false;
                return;
            }
            if (selected.size > 0) {
                toggle(id);
                return;
            }
            onActivate?.();
        },
    });

    return {
        isSelecting: selected.size > 0,
        selectedIds: Array.from(selected),
        count: selected.size,
        isSelected: (id: string) => selected.has(id),
        toggle,
        clear,
        getItemProps,
    };
};
