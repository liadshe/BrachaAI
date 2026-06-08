import { useState, useEffect } from 'react';
import apiClient from '../../services/apiClient';
import BottomNav from '@/components/BottomNav';
import styles from './TasksPage.module.css';

interface Task {
    _id: string;
    title: string;
    description?: string;
    priority: 'LOW' | 'MEDIUM' | 'HIGH';
    status: 'todo' | 'in-progress' | 'done';
    dueDate?: string;
    completed: boolean;
    contactId?: {
        _id: string;
        name: string;
    };
}

const TasksPage: React.FC = () => {
    const [tasks, setTasks] = useState<Task[]>([]);
    const [clients, setClients] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);
    const [filter, setFilter] = useState<'All' | 'Today' | 'Overdue' | 'Done'>('All');

    // Edit Modal State
    const [isEditModalOpen, setIsEditModalOpen] = useState(false);
    const [editingTask, setEditingTask] = useState<Task | null>(null);
    const [editTitle, setEditTitle] = useState('');
    const [editDescription, setEditDescription] = useState('');
    const [editPriority, setEditPriority] = useState<'LOW' | 'MEDIUM' | 'HIGH'>('MEDIUM');
    const [editDueDate, setEditDueDate] = useState('');
    const [editContactId, setEditContactId] = useState('');

    useEffect(() => {
        const fetchData = async () => {
            try {
                const [tasksRes, clientsRes] = await Promise.all([
                    apiClient.get('/tasks'),
                    apiClient.get('/clients')
                ]);
                setTasks(tasksRes.data);
                setClients(clientsRes.data);
            } catch (error) {
                console.error('Error fetching tasks data:', error);
            } finally {
                setLoading(false);
            }
        };
        fetchData();
    }, []);

    const filteredTasks = tasks.filter(task => {
        if (filter === 'Done') return task.completed;
        if (filter === 'Today') {
            if (task.completed) return false;
            if (!task.dueDate) return false;
            // Compare string representations
            const todayStr = new Date().toISOString().split('T')[0];
            return task.dueDate.startsWith(todayStr);
        }
        if (filter === 'Overdue') {
            if (task.completed) return false;
            if (!task.dueDate) return false;
            const now = new Date().toISOString();
            return task.dueDate < now;
        }
        return true;
    });

    const toggleTask = async (id: string) => {
        const taskToToggle = tasks.find(t => t._id === id);
        if (!taskToToggle) return;
        const newCompleted = !taskToToggle.completed;

        // Optimistic UI update
        setTasks(prev => prev.map(t => t._id === id ? { ...t, completed: newCompleted } : t));

        try {
            await apiClient.patch(`/tasks/${id}`, { completed: newCompleted });
        } catch (error) {
            console.error('Error updating task completed state:', error);
            // Revert state on error
            setTasks(prev => prev.map(t => t._id === id ? { ...t, completed: !newCompleted } : t));
        }
    };

    const handleEditClick = (task: Task) => {
        setEditingTask(task);
        setEditTitle(task.title);
        setEditDescription(task.description || '');
        setEditPriority(task.priority);
        // Extract YYYY-MM-DD for date input if it's an ISO string
        setEditDueDate(task.dueDate ? task.dueDate.split('T')[0] : '');
        setEditContactId(task.contactId?._id || '');
        setIsEditModalOpen(true);
    };

    const handleSaveEdit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!editingTask) return;

        try {
            const response = await apiClient.patch(`/tasks/${editingTask._id}`, {
                title: editTitle,
                description: editDescription,
                priority: editPriority,
                dueDate: editDueDate || undefined,
                contactId: editContactId || undefined
            });

            setTasks(prev => prev.map(t => t._id === editingTask._id ? response.data : t));
            setIsEditModalOpen(false);
            setEditingTask(null);
        } catch (error) {
            console.error('Error saving task changes:', error);
        }
    };

    const formatDateDisplay = (dateString?: string) => {
        if (!dateString) return 'No due date';
        try {
            const date = new Date(dateString);
            return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
        } catch (e) {
            return dateString;
        }
    };

    const isOverdue = (task: Task) => {
        if (task.completed || !task.dueDate) return false;
        const now = new Date();
        return new Date(task.dueDate) < now;
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
                        <div key={task._id} className={`${styles.taskCard} ${isOverdue(task) ? styles.overdueCard : ''}`}>
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
                                    <button className={styles.editBtn} onClick={() => handleEditClick(task)}>
                                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                                        </svg>
                                    </button>
                                </div>
                                <p className={styles.contactName}>{task.contactId?.name || 'No Contact'}</p>
                                {task.description && (
                                    <p style={{ fontSize: '14px', color: '#64748b', margin: '0 0 12px 0', lineHeight: '1.4' }}>{task.description}</p>
                                )}
                                <div className={styles.taskFooter}>
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.calendarIcon}>
                                        <rect width="18" height="18" x="3" y="4" rx="2" ry="2" />
                                        <line x1="16" x2="16" y1="2" y2="6" />
                                        <line x1="8" x2="8" y1="2" y2="6" />
                                        <line x1="3" x2="21" y1="10" y2="10" />
                                    </svg>
                                    <span className={`${styles.dueDate} ${isOverdue(task) ? styles.overdueText : ''}`}>
                                        {formatDateDisplay(task.dueDate)}
                                    </span>
                                </div>
                            </div>
                        </div>
                    ))
                ) : (
                    <p className={styles.statusMessage}>No tasks found.</p>
                )}
            </main>

            {/* Edit Task Modal */}
            {isEditModalOpen && (
                <div className={styles.modalOverlay}>
                    <div className={styles.modalContent}>
                        <h2 className={styles.modalTitle}>Edit Task</h2>
                        <form onSubmit={handleSaveEdit} className={styles.modalForm}>
                            <div className={styles.inputGroup}>
                                <label className={styles.inputLabel}>Title</label>
                                <input
                                    type="text"
                                    value={editTitle}
                                    onChange={(e) => setEditTitle(e.target.value)}
                                    className={styles.textField}
                                    required
                                />
                            </div>

                            <div className={styles.inputGroup}>
                                <label className={styles.inputLabel}>Description</label>
                                <textarea
                                    value={editDescription}
                                    onChange={(e) => setEditDescription(e.target.value)}
                                    className={styles.textareaField}
                                />
                            </div>

                            <div className={styles.inputGroup}>
                                <label className={styles.inputLabel}>Priority</label>
                                <select
                                    value={editPriority}
                                    onChange={(e) => setEditPriority(e.target.value as any)}
                                    className={styles.selectField}
                                >
                                    <option value="LOW">Low</option>
                                    <option value="MEDIUM">Medium</option>
                                    <option value="HIGH">High</option>
                                </select>
                            </div>

                            <div className={styles.inputGroup}>
                                <label className={styles.inputLabel}>Due Date</label>
                                <input
                                    type="date"
                                    value={editDueDate}
                                    onChange={(e) => setEditDueDate(e.target.value)}
                                    className={styles.textField}
                                />
                            </div>

                            <div className={styles.inputGroup}>
                                <label className={styles.inputLabel}>Client / Contact</label>
                                <select
                                    value={editContactId}
                                    onChange={(e) => setEditContactId(e.target.value)}
                                    className={styles.selectField}
                                >
                                    <option value="">No Contact</option>
                                    {clients.map(client => (
                                        <option key={client._id} value={client._id}>{client.name}</option>
                                    ))}
                                </select>
                            </div>

                            <div className={styles.modalActions}>
                                <button type="button" className={styles.cancelBtn} onClick={() => setIsEditModalOpen(false)}>
                                    Cancel
                                </button>
                                <button type="submit" className={styles.saveBtn}>
                                    Save Task
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <BottomNav />
        </div>
    );
};

export default TasksPage;
