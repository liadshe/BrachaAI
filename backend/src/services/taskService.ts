import Task from '../models/Task';

export const createTasksFromAi = async (userId: string, contactId: string, tasks: any[]) => {
    const taskPromises = tasks.map(item => {
        return Task.create({
            userId,
            contactId,
            description: item.task, 
            priority: 'medium',
            status: 'todo',
            // If the AI gave us a deadline, we save it (optional)
            deadline: item.deadline ? new Date() : undefined 
        });
    });
    
    return await Promise.all(taskPromises);
};