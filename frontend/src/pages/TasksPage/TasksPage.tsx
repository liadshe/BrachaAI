import { useState } from 'react';
import BottomNav from '@/components/BottomNav';
import styles from './TasksPage.module.css';

interface Task {
    id: string;
    title: string;
    contact: string;
    priority: 'high' | 'medium' | 'low';
    dueDate: string;
    completed: boolean;
}

const TasksPage: React.FC = () => {
    const [filter, setFilter] = useState<'All' | 'Today' | 'Overdue' | 'Done'>('All');
    const [tasks, setTasks] = useState<Task[]>([
        { id: '1', title: 'Send invoice to Yossi', contact: 'Yossi Ben David', priority: 'high', dueDate: 'Today', completed: false },
        { id: '2', title: 'Call back Sarah', contact: 'Sarah Levi', priority: 'high', dueDate: 'Today', completed: false },
        { id: '3', title: 'Review contract', contact: 'David Cohen', priority: 'medium', dueDate: 'Tomorrow', completed: false },
        { id: '4', title: 'Send proposal document', contact: 'Michael Shapiro', priority: 'high', dueDate: '2 days ago', completed: false },
    ]);

    const toggleTask = (id: string) => {
        setTasks(prev => prev.map(t => t.id === id ? { ...t, completed: !t.completed } : t));
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
                {tasks.map(task => (
                    <div key={task.id} className={`${styles.taskCard} ${task.dueDate.includes('ago') ? styles.overdueCard : ''}`}>
                        <label className={styles.checkboxContainer}>
                            <input
                                type="checkbox"
                                checked={task.completed}
                                onChange={() => toggleTask(task.id)}
                            />
                            <span className={styles.checkmark}></span>
                        </label>
                        <div className={styles.taskContent}>
                            <div className={styles.taskHeader}>
                                <h3 className={styles.taskTitle}>{task.title}</h3>
                                <span className={`${styles.priorityTag} ${styles[task.priority]}`}>{task.priority}</span>
                                <button className={styles.editBtn}>
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                                    </svg>
                                </button>
                            </div>
                            <p className={styles.contactName}>{task.contact}</p>
                            <div className={styles.taskFooter}>
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.calendarIcon}>
                                    <rect width="18" height="18" x="3" y="4" rx="2" ry="2" />
                                    <line x1="16" x2="16" y1="2" y2="6" />
                                    <line x1="8" x2="8" y1="2" y2="6" />
                                    <line x1="3" x2="21" y1="10" y2="10" />
                                </svg>
                                <span className={`${styles.dueDate} ${task.dueDate.includes('ago') ? styles.overdueText : ''}`}>
                                    {task.dueDate}
                                </span>
                            </div>
                        </div>
                    </div>
                ))}
            </main>

            <BottomNav />
        </div>
    );
};

export default TasksPage;
