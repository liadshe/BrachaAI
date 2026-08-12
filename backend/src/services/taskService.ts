import Task from '../models/Task';

const PRIORITY_RANK: Record<string, number> = { LOW: 0, MEDIUM: 1, HIGH: 2 };

const rank = (priority: unknown) => PRIORITY_RANK[String(priority).toUpperCase()] ?? 0;

/**
 * Key used to decide whether two AI-extracted tasks are the same action item.
 * The model emits one entry per mention, so the same task discussed three times
 * in a call arrives three times with only cosmetic differences — casing, spacing,
 * or a trailing period. Leading/trailing punctuation is stripped in a
 * Unicode-aware way so Hebrew titles collapse the same as English ones.
 */
const dedupeKey = (title: string) =>
    title
        .trim()
        .toLowerCase()
        .replace(/\s+/g, ' ')
        .replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '');

/** The only shape the prompt allows the model to answer a deadline in. */
const DUE_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * The deadline the model resolved from the call — "tomorrow", "by next Friday" — reduced to
 * a calendar date, or undefined if it did not give a usable one.
 *
 * The prompt asks for YYYY-MM-DD against the current date, but it is still a language model:
 * it can answer in Hebrew, answer "", or name a day that does not exist. As with
 * `parseFilenameDate`, the regex proves the fields are digits and nothing more, so the value
 * is round-tripped through Date.UTC and rejected if the rollover moved it. A task with no
 * deadline is honest; a task due the 30th of February is not.
 */
const normalizeDueDate = (value: unknown): string | undefined => {
    if (typeof value !== 'string') return undefined;

    const trimmed = value.trim();
    const match = DUE_DATE.exec(trimmed);
    if (!match) {
        if (trimmed !== '') {
            console.error(`AI returned unusable dueDate "${trimmed}", leaving the task undated`);
        }
        return undefined;
    }

    const [, year, month, day] = match.map(Number);
    const naive = new Date(Date.UTC(year, month - 1, day));
    const isRealDate =
        naive.getUTCFullYear() === year &&
        naive.getUTCMonth() === month - 1 &&
        naive.getUTCDate() === day;

    if (!isRealDate) {
        console.error(`AI returned dueDate "${trimmed}", which is not a real date, leaving the task undated`);
        return undefined;
    }

    return trimmed;
};

/** The tighter of two deadlines. Both are YYYY-MM-DD, so string order is date order. */
const earlier = (a?: string, b?: string) => {
    if (!a) return b;
    if (!b) return a;
    return a < b ? a : b;
};

const dedupeAiTasks = (tasks: any[]) => {
    const merged = new Map<string, any>();

    for (const item of tasks) {
        const title = typeof item?.title === 'string' ? item.title.trim() : '';
        const key = dedupeKey(title);
        if (!key) continue;

        const dueDate = normalizeDueDate(item?.dueDate);

        const existing = merged.get(key);
        if (!existing) {
            merged.set(key, { ...item, title, dueDate });
            continue;
        }

        // Same action item, mentioned again. Keep the most urgent priority, the most
        // detailed description and the tightest deadline across mentions, at the first
        // mention's position. A deadline stated once and omitted on the other mentions
        // still counts — the caller only said "by Friday" out loud a single time.
        if (rank(item?.priority) > rank(existing.priority)) {
            existing.priority = item.priority;
        }
        existing.dueDate = earlier(existing.dueDate, dueDate);
        const description = typeof item?.description === 'string' ? item.description : '';
        if (description.trim().length > String(existing.description ?? '').trim().length) {
            existing.description = item.description;
        }
    }

    return [...merged.values()];
};

export const createTasksFromAi = async (userId: string, contactId: string, tasks: any[]) => {
    const taskPromises = dedupeAiTasks(tasks).map(item => {
        return Task.create({
            userId,
            contactId,
            title: item.title,
            description: item.description,
            priority: item.priority,
            dueDate: item.dueDate,
            status: 'todo',
        });
    });

    return await Promise.all(taskPromises);
};
