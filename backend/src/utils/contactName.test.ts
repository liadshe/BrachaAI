import { describe, it, expect } from 'vitest';

import { stripCallPrefix } from './contactName';

describe('stripCallPrefix', () => {
    it('drops the recorder label so the contact is just the person', () => {
        expect(stripCallPrefix('Call Mom')).toBe('Mom');
        expect(stripCallPrefix('call Mom')).toBe('Mom');
        expect(stripCallPrefix('CALL Mom')).toBe('Mom');
        expect(stripCallPrefix('Call  Mom')).toBe('Mom');
        expect(stripCallPrefix('Call_Mom')).toBe('Mom');
    });

    it('drops only the leading label, and only once', () => {
        expect(stripCallPrefix('Call Call Center')).toBe('Call Center');
    });

    it('leaves a name that merely starts with those letters alone', () => {
        expect(stripCallPrefix('Callie')).toBe('Callie');
        expect(stripCallPrefix('Caller ID')).toBe('Caller ID');
        expect(stripCallPrefix('Recall Dana')).toBe('Recall Dana');
    });

    it('keeps the prefix when it is the whole name, rather than going nameless', () => {
        expect(stripCallPrefix('Call')).toBe('Call');
        expect(stripCallPrefix('Call ')).toBe('Call');
    });

    it('passes through anything it is not meant to touch', () => {
        expect(stripCallPrefix('Mom')).toBe('Mom');
        expect(stripCallPrefix('  Mom  ')).toBe('Mom');
        expect(stripCallPrefix('')).toBe('');
        expect(stripCallPrefix(undefined)).toBe(undefined);
        expect(stripCallPrefix(null)).toBe(null);
    });
});
