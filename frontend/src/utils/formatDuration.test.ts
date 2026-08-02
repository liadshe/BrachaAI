import { describe, it, expect } from 'vitest';
import { formatDuration } from './formatDuration';

describe('formatDuration', () => {
    it('formats under a minute without collapsing to zero', () => {
        // The bug this replaces: Math.round(45 / 60) rendered "0 min".
        expect(formatDuration(45)).toBe('0:45');
    });

    it('zero-pads the seconds', () => {
        expect(formatDuration(65)).toBe('1:05');
    });

    it('formats minutes and seconds', () => {
        expect(formatDuration(272)).toBe('4:32');
    });

    it('switches to hours at exactly an hour', () => {
        expect(formatDuration(3600)).toBe('1:00:00');
    });

    it('stays in minutes just under an hour', () => {
        expect(formatDuration(3599)).toBe('59:59');
    });

    it('zero-pads the minutes in the hours form', () => {
        expect(formatDuration(4360)).toBe('1:12:40');
    });

    it.each([
        ['undefined', undefined],
        ['zero', 0],
        ['a negative number', -5],
        ['NaN', NaN],
        ['Infinity', Infinity],
    ])('reports unknown for %s', (_label, seconds) => {
        expect(formatDuration(seconds as number | undefined)).toBeNull();
    });

    it('reports unknown for null, which is what the API sends for an unmeasured call', () => {
        expect(formatDuration(null as unknown as undefined)).toBeNull();
    });

    it('truncates a fractional value rather than rendering a decimal', () => {
        expect(formatDuration(90.7)).toBe('1:30');
    });
});
