import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useMultiSelect, LONG_PRESS_MS, MOVE_CANCEL_PX } from './useMultiSelect';

// The handlers are invoked directly rather than through fireEvent because
// jsdom has no PointerEvent constructor, so synthesized pointer events lose
// their clientX/clientY and the move-cancel test would silently pass.
const press = (at = { clientX: 100, clientY: 100 }) => at;

describe('useMultiSelect', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('starts out not selecting', () => {
        const { result } = renderHook(() => useMultiSelect());

        expect(result.current.isSelecting).toBe(false);
        expect(result.current.count).toBe(0);
    });

    it('enters selection mode with the pressed item after a long press', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(true);
        expect(result.current.isSelected('call-a')).toBe(true);
        expect(result.current.count).toBe(1);
    });

    it('does not enter selection mode when released before the threshold', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS - 50);
            result.current.getItemProps('call-a').onPointerUp();
            vi.advanceTimersByTime(500);
        });

        expect(result.current.isSelecting).toBe(false);
    });

    it('cancels the pending long press when the pointer moves past the threshold', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press({ clientX: 100, clientY: 100 }));
            result.current.getItemProps('call-a').onPointerMove({
                clientX: 100,
                clientY: 100 + MOVE_CANCEL_PX + 5,
            });
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(false);
    });

    it('tolerates small movement within the threshold', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press({ clientX: 100, clientY: 100 }));
            result.current.getItemProps('call-a').onPointerMove({ clientX: 102, clientY: 103 });
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(true);
    });

    it('cancels the pending long press on pointer cancel', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            result.current.getItemProps('call-a').onPointerCancel();
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(false);
    });

    it('suppresses the click that follows a long press', () => {
        const onActivate = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a', onActivate).onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.getItemProps('call-a', onActivate).onPointerUp();
            result.current.getItemProps('call-a', onActivate).onClick();
        });

        // The long press already selected it; the trailing click must not
        // immediately deselect it.
        expect(onActivate).not.toHaveBeenCalled();
        expect(result.current.isSelected('call-a')).toBe(true);
    });

    it('runs the activate callback on a plain tap outside selection mode', () => {
        const onActivate = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a', onActivate).onClick();
        });

        expect(onActivate).toHaveBeenCalledTimes(1);
    });

    it('toggles instead of activating while in selection mode', () => {
        const onActivate = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.getItemProps('call-a').onPointerUp();
            result.current.getItemProps('call-a').onClick();
        });
        act(() => {
            result.current.getItemProps('call-b', onActivate).onClick();
        });

        expect(onActivate).not.toHaveBeenCalled();
        expect(result.current.isSelected('call-b')).toBe(true);
        expect(result.current.count).toBe(2);
    });

    it('exits selection mode when the last item is deselected', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.toggle('call-a');
        });

        expect(result.current.isSelecting).toBe(false);
        expect(result.current.count).toBe(0);
    });

    it('clears every selection', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.toggle('call-b');
        });
        act(() => {
            result.current.clear();
        });

        expect(result.current.isSelecting).toBe(false);
        expect(result.current.selectedIds).toEqual([]);
    });

    it('does not swallow the next tap when a long press click lands on a common ancestor', () => {
        // Regression: if the pointer drags off the card after the long-press
        // timer fires (selecting the item) but before release, the browser's
        // synthesized click lands on an ancestor above the card, not on the
        // card's own onClick. That leaves suppressClickRef stuck at true,
        // silently swallowing the very next tap on any card.
        const onActivateB = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            // Click never fires on 'call-a' here (it landed on an ancestor
            // instead) — only the pointerUp reaches this card.
            result.current.getItemProps('call-a').onPointerUp();
        });

        expect(result.current.isSelected('call-a')).toBe(true);
        expect(result.current.count).toBe(1);

        act(() => {
            result.current.getItemProps('call-b', onActivateB).onPointerDown(press());
            result.current.getItemProps('call-b', onActivateB).onPointerUp();
            result.current.getItemProps('call-b', onActivateB).onClick();
        });

        // We're already in selection mode (call-a selected), so tapping
        // call-b must toggle it in — not be silently swallowed.
        expect(result.current.isSelected('call-b')).toBe(true);
        expect(result.current.count).toBe(2);
    });

    it('exposes the selected ids', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.toggle('call-a');
        });
        act(() => {
            result.current.toggle('call-b');
        });

        expect(result.current.selectedIds.sort()).toEqual(['call-a', 'call-b']);
    });
});
