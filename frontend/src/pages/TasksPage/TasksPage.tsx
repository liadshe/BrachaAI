import { useState, useEffect } from 'react';
import axios from 'axios';
import BottomNav from '@/components/BottomNav';
import styles from './TasksPage.module.css';

interface Task {
    _id: string;
    title: string;
    priority: 'LOW' | 'MEDIUM' | 'HIGH';
    status: 'todo' | 'in-progress' | 'done';
    dueDate?: string;
    completed: boolean;
    contactName?: string;
}

const TasksPage: React.FC = () => {
    const [tasks, setTasks] = useState<Task[]>([]);
    const [loading, setLoading] = useState(true);
    const [filter, setFilter] = useState<'All' | 'Today' | 'Overdue' | 'Done'>('All');

    useEffect(() => {
        const fetchTasks = async () => {
            try {
                const response = await axios.get('http://localhost:3000/api/tasks');
                setTasks(response.data);
            } catch (error) {
                console.error('Error fetching tasks:', error);
            } finally {
                setLoading(false);
            }
        };
        fetchTasks();
    }, []);

    const filteredTasks = tasks.filter(task => {
        if (filter === 'Done') return task.completed;
        if (filter === 'Today') return !task.completed && task.dueDate === 'Today'; // Simplification
        if (filter === 'Overdue') return !task.completed && task.dueDate?.includes('ago');
        return true;
    });

    const toggleTask = (id: string) => {
        // Optimistic UI update
        setTasks(prev => prev.map(t => t._id === id ? { ...t, completed: !t.completed } : t));
        // TODO: Implement backend patch
    };

    return (
        <div className={styles.pageWrapper}>
            <header className={styles.header}>
                <h1 className={styles.title}>Tasks</h1>
                <div className={styles.filters}>
                    {(['All', 'Today', 'Overdue', 'Done'] as const).map(f => (
                        <button
                            key={f}
                            className={`${styles.filterBtn} ${filter === f ? styles.active : ''}`}
                            onClick={() => setFilter(f)}
                        >
                            {f}
                        </button>
                    ))}
                </div>
            </header>

            <main className={styles.taskList}>
                {loading ? (
                    <p className={styles.statusMessage}>Loading tasks...</p>
                ) : filteredTasks.length > 0 ? (
                    filteredTasks.map(task => (
                        <div key={task._id} className={`${styles.taskCard} ${task.dueDate?.includes('ago') ? styles.overdueCard : ''}`}>
                            <label className={styles.checkboxContainer}>
                                <input
                                    type="checkbox"
                                    checked={task.completed}
                                    onChange={() => toggleTask(task._id)}
                                />
                                <span className={styles.checkmark}></span>
                            </label>
                            <div className={styles.taskContent}>
                                <div className={styles.taskHeader}>
                                    <h3 className={styles.taskTitle}>{task.title}</h3>
                                    <span className={`${styles.priorityTag} ${styles[task.priority.toLowerCase()]}`}>{task.priority}</span>
                                    <button className={styles.editBtn}>
                                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                                        </svg>
                                    </button>
                                </div>
                                <p className={styles.contactName}>{task.contactName || 'No Contact'}</p>
                                <div className={styles.taskFooter}>
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.calendarIcon}>
                                        <rect width="18" height="18" x="3" y="4" rx="2" ry="2" />
                                        <line x1="16" x2="16" y1="2" y2="6" />
                                        <line x1="8" x2="8" y1="2" y2="6" />
                                        <line x1="3" x2="21" y1="10" y2="10" />
                                    </svg>
                                    <span className={`${styles.dueDate} ${task.dueDate?.includes('ago') ? styles.overdueText : ''}`}>
                                        {task.dueDate || 'No due date'}
                                    </span>
                                </div>
                            </div>
                        </div>
                    ))
                ) : (
                    <p className={styles.statusMessage}>No tasks found.</p>
                )}
            </main>

            <BottomNav />
        </div>
    );
};

export default TasksPage;
