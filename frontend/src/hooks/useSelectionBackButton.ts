import { useEffect, useRef } from 'react';

/**
 * Makes the Android back button exit selection mode instead of leaving the
 * screen, by pushing a throwaway history entry while selecting and popping it
 * when selection ends.
 *
 * The pushed entry has the same URL as the current one, so React Router sees
 * no location change and does not navigate.
 */
export const useSelectionBackButton = (active: boolean, onExit: () => void) => {
    const unmountingRef = useRef(false);

    // Declared before the effect below so that on unmount React runs this
    // cleanup first, letting the other one tell "selection ended" apart from
    // "the page is going away".
    useEffect(() => () => {
        unmountingRef.current = true;
    }, []);

    useEffect(() => {
        if (!active) return;

        window.history.pushState({ ...window.history.state, brachaSelection: true }, '');

        const handlePop = () => onExit();
        window.addEventListener('popstate', handlePop);

        return () => {
            window.removeEventListener('popstate', handlePop);
            // On unmount the user is navigating somewhere; consuming our entry
            // here would cancel that navigation and trap them on this page.
            if (unmountingRef.current) return;
            if ((window.history.state as { brachaSelection?: boolean } | null)?.brachaSelection) {
                window.history.back();
            }
        };
        // onExit must be a stable reference (the `clear` from useMultiSelect is
        // wrapped in useCallback); an unstable one would push a duplicate entry
        // on every render.
    }, [active, onExit]);
};
