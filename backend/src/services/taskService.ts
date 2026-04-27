import Task from '../models/Task';

export const createTasksFromAi = async (userId: string, contactId: string, tasks: any[]) => {
    const taskPromises = tasks.map(item => {
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