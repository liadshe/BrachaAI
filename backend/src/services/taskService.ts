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

const dedupeAiTasks = (tasks: any[]) => {
    const merged = new Map<string, any>();

    for (const item of tasks) {
        const title = typeof item?.title === 'string' ? item.title.trim() : '';
        const key = dedupeKey(title);
        if (!key) continue;

        const existing = merged.get(key);
        if (!existing) {
            merged.set(key, { ...item, title });
            continue;
        }

        // Same action item, mentioned again. Keep the most urgent priority and the
        // most detailed description across mentions, at the first mention's position.
        if (rank(item?.priority) > rank(existing.priority)) {
            existing.priority = item.priority;
        }
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
            status: 'todo',
        });
    });

    return await Promise.all(taskPromises);
};
