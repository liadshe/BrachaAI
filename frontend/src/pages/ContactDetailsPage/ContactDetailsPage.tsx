import { useCallback, useRef, useState, useEffect } from 'react';
import { useParams, useSearchParams, Link, useNavigate } from 'react-router-dom';
import apiClient from '../../services/apiClient';
import BottomNav from '@/components/BottomNav';
import SelectionBar from '@/components/SelectionBar';
import ConfirmDialog from '@/components/ConfirmDialog';
import { useMultiSelect } from '@/hooks/useMultiSelect';
import { useSelectionBackButton } from '@/hooks/useSelectionBackButton';
import { useCallDeletion } from '@/hooks/useCallDeletion';
import { formatDuration } from '@/utils/formatDuration';
import styles from './ContactDetailsPage.module.css';

/** How long an arrived-at call stays visually marked before settling into the list. */
export const HIGHLIGHT_MS = 2000;

interface Contact {
    _id: string;
    name: string;
    phone: string;
    email?: string;
}

interface Call {
    _id: string;
    callSummary?: string;
    fullTranscript: string;
    callDateTime: string;
    callLength?: number;
    callType?: 'incoming' | 'outgoing' | 'missed';
}

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

const ContactDetailsPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();

    // Set when arriving from a call card on the home screen: /contacts/:id?call=<callId>.
    const focusCallId = searchParams.get('call');
    const [highlightedCallId, setHighlightedCallId] = useState<string | null>(null);
    const callNodes = useRef(new Map<string, HTMLDivElement>());
    const focusHandledRef = useRef<string | null>(null);

    const [contact, setContact] = useState<Contact | null>(null);
    const [calls, setCalls] = useState<Call[]>([]);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState<'calls' | 'tasks'>('calls');
    const [expandedCallIds, setExpandedCallIds] = useState<Set<string>>(new Set());

    // Task edit modal states
    const [isEditModalOpen, setIsEditModalOpen] = useState(false);
    const [editingTask, setEditingTask] = useState<Task | null>(null);
    const [editTitle, setEditTitle] = useState('');
    const [editDescription, setEditDescription] = useState('');
    const [editPriority, setEditPriority] = useState<'LOW' | 'MEDIUM' | 'HIGH'>('MEDIUM');
    const [editDueDate, setEditDueDate] = useState('');

    const callSelection = useMultiSelect();
    const [isDeleteCallsOpen, setIsDeleteCallsOpen] = useState(false);
    const [isDeleteContactOpen, setIsDeleteContactOpen] = useState(false);
    const [isDeletingContact, setIsDeletingContact] = useState(false);
    const [contactError, setContactError] = useState<string | null>(null);

    // Back must also close the confirm dialog — otherwise the selection
    // clears but the dialog stays open, now reading "Delete 0 calls?" with
    // an enabled Delete button that would POST an empty id list.
    const exitSelection = useCallback(() => {
        setIsDeleteCallsOpen(false);
        callSelection.clear();
    }, [callSelection.clear]);

    useSelectionBackButton(callSelection.isSelecting, exitSelection);

    const callDeletion = useCallDeletion({
        onDeleted: (deletedIds) => {
            setCalls(prev => prev.filter(call => !deletedIds.has(call._id)));
        },
        onRefetch: async () => {
            const callsRes = await apiClient.get('/calls');
            setCalls(callsRes.data.filter((c: any) => c.contactId?._id === id));
        },
    });

    useEffect(() => {
        const fetchDetails = async () => {
            try {
                const [contactRes, callsRes, tasksRes] = await Promise.all([
                    apiClient.get(`/contacts/${id}`),
                    apiClient.get('/calls'),
                    apiClient.get('/tasks')
                ]);
                setContact(contactRes.data);
                
                const filteredCalls = callsRes.data.filter((c: any) => c.contactId?._id === id);
                setCalls(filteredCalls);
                
                const filteredTasks = tasksRes.data.filter((t: any) => t.contactId?._id === id);
                setTasks(filteredTasks);
            } catch (error) {
                console.error('Error fetching details:', error);
            } finally {
                setLoading(false);
            }
        };
        fetchDetails();
    }, [id]);

    /**
     * Brings the deep-linked call into view and marks it, so arriving from the home
     * screen lands on the call the user actually tapped rather than the top of the list.
     *
     * Depends on `calls` because the node cannot be scrolled to before the list that
     * renders it is in state. `focusHandledRef` then pins this to the first render that
     * has the call: without it, every later change to `calls` — deleting one, most
     * obviously — would yank the page back and re-flash the highlight.
     *
     * A `?call=` that matches nothing (a link kept after the call was deleted) is left
     * alone: the contact page is still the right destination, just without the marker.
     */
    useEffect(() => {
        if (!focusCallId || focusHandledRef.current === focusCallId) return;

        const node = callNodes.current.get(focusCallId);
        if (!node) return;

        focusHandledRef.current = focusCallId;
        node.scrollIntoView({ behavior: 'smooth', block: 'center' });
        setHighlightedCallId(focusCallId);

        const timer = setTimeout(() => setHighlightedCallId(null), HIGHLIGHT_MS);
        return () => clearTimeout(timer);
    }, [focusCallId, calls]);

    const handleDeleteSelectedCalls = async () => {
        await callDeletion.deleteCalls(callSelection.selectedIds);
        callSelection.clear();
        setIsDeleteCallsOpen(false);
    };

    const contactDeleteMessage = () => {
        const parts: string[] = [];
        if (calls.length > 0) {
            parts.push(`${calls.length} ${calls.length === 1 ? 'call' : 'calls'}`);
        }
        if (tasks.length > 0) {
            parts.push(`${tasks.length} ${tasks.length === 1 ? 'task' : 'tasks'}`);
        }
        // Omit the clause entirely rather than saying "0 calls and 0 tasks".
        const damage = parts.length > 0 ? `This also deletes ${parts.join(' and ')}. ` : '';
        return `${damage}This can't be undone.`;
    };

    const handleDeleteContact = async () => {
        setIsDeletingContact(true);
        setContactError(null);

        try {
            await apiClient.delete(`/contacts/${id}`);
            navigate('/contacts');
        } catch (error: any) {
            console.error('Error deleting contact:', error);

            if (error?.response?.status === 404) {
                // Already gone (e.g. deleted from another device) — the
                // desired end state is already reached, so just leave.
                navigate('/contacts');
                return;
            }

            setContactError('Could not delete this contact. Please try again.');
            setIsDeleteContactOpen(false);
            setIsDeletingContact(false);
        }
    };

    const toggleCallTranscript = (callId: string) => {
        setExpandedCallIds(prev => {
            const next = new Set(prev);
            if (next.has(callId)) {
                next.delete(callId);
            } else {
                next.add(callId);
            }
            return next;
        });
    };

    const toggleTask = async (taskId: string) => {
        const taskToToggle = tasks.find(t => t._id === taskId);
        if (!taskToToggle) return;
        const newCompleted = !taskToToggle.completed;

        // Optimistic update
        setTasks(prev => prev.map(t => t._id === taskId ? { ...t, completed: newCompleted } : t));

        try {
            await apiClient.patch(`/tasks/${taskId}`, { completed: newCompleted });
        } catch (error) {
            console.error('Error updating task:', error);
            // Revert on error
            setTasks(prev => prev.map(t => t._id === taskId ? { ...t, completed: !newCompleted } : t));
        }
    };

    const handleEditClick = (task: Task) => {
        setEditingTask(task);
        setEditTitle(task.title);
        setEditDescription(task.description || '');
        setEditPriority(task.priority);
        setEditDueDate(task.dueDate ? task.dueDate.split('T')[0] : '');
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
                dueDate: editDueDate || undefined
            });

            setTasks(prev => prev.map(t => t._id === editingTask._id ? response.data : t));
            setIsEditModalOpen(false);
            setEditingTask(null);
        } catch (error) {
            console.error('Error saving task edits:', error);
        }
    };

    const getRelativeDay = (dateStr?: string) => {
        if (!dateStr) return '';
        const date = new Date(dateStr);
        const today = new Date();
        const dDate = new Date(date.getFullYear(), date.getMonth(), date.getDate());
        const dToday = new Date(today.getFullYear(), today.getMonth(), today.getDate());
        const diffTime = dToday.getTime() - dDate.getTime();
        const diffDays = Math.floor(diffTime / (1000 * 60 * 60 * 24));

        if (diffDays === 0) return 'Today';
        if (diffDays === 1) return 'Yesterday';
        if (diffDays < 7 && diffDays > 0) return `${diffDays} days ago`;
        return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    };

    const formatTime = (dateStr?: string) => {
        if (!dateStr) return '';
        return new Date(dateStr).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    };

    /**
     * A plain "Call" for a direction the device could not determine. The old ternary fell
     * through to 'Incoming Call' for anything that wasn't outgoing or missed, so every call
     * with no direction — which was all of them whenever the call log was unreadable —
     * claimed to be incoming.
     */
    const formatCallType = (callType?: Call['callType']) => {
        switch (callType) {
            case 'outgoing': return 'Outgoing Call';
            case 'incoming': return 'Incoming Call';
            case 'missed': return 'Missed Call';
            default: return 'Call';
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

    const renderTranscript = (transcript: string) => {
        const lines = transcript.split('\n');
        return (
            <div className={styles.transcriptContainer}>
                {lines.map((line, idx) => {
                    const parts = line.split(':');
                    if (parts.length > 1) {
                        const speaker = parts[0].trim();
                        const text = parts.slice(1).join(':').trim();
                        const isCaller = speaker.toLowerCase().includes('caller') || speaker.toLowerCase().includes('client') || speaker.toLowerCase().includes('contact') || speaker.toLowerCase().includes('david');
                        return (
                            <div key={idx} className={`${styles.chatMessage} ${isCaller ? styles.callerMsg : styles.receiverMsg}`}>
                                <span className={styles.speakerName}>{speaker}</span>
                                <p className={styles.messageText}>{text}</p>
                            </div>
                        );
                    }
                    return <p key={idx} className={styles.rawLine}>{line}</p>;
                })}
            </div>
        );
    };

    const openTasks = tasks.filter(t => !t.completed);
    const doneTasks = tasks.filter(t => t.completed);

    if (loading) {
        return (
            <div className={styles.pageWrapper}>
                <p className={styles.statusMessage}>Loading details...</p>
                <BottomNav />
            </div>
        );
    }

    if (!contact) {
        return (
            <div className={styles.pageWrapper}>
                <p className={styles.statusMessage}>Contact not found.</p>
                <BottomNav />
            </div>
        );
    }

    return (
        <div className={styles.pageWrapper}>
            {callSelection.isSelecting && (
                <>
                    <SelectionBar
                        count={callSelection.count}
                        onCancel={callSelection.clear}
                        onDelete={() => setIsDeleteCallsOpen(true)}
                    />
                    <div className={styles.selectionSpacer} />
                </>
            )}

            {/* Header section matching David Cohen details screen */}
            <div className={styles.header}>
                <div className={styles.headerTopRow}>
                    <Link to="/contacts" className={styles.backLink}>
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                            <line x1="19" y1="12" x2="5" y2="12"></line>
                            <polyline points="12 19 5 12 12 5"></polyline>
                        </svg>
                        <span>Back to Contacts</span>
                    </Link>

                    <button
                        type="button"
                        className={styles.deleteContactBtn}
                        onClick={() => setIsDeleteContactOpen(true)}
                        aria-label="Delete contact"
                    >
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                            <polyline points="3 6 5 6 21 6" />
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                        </svg>
                    </button>
                </div>

                {contactError && <p className={styles.errorMessage}>{contactError}</p>}

                <h1 className={styles.contactName}>{contact.name}</h1>
                <div className={styles.phoneRow}>
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.phoneIcon}>
                        <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                    </svg>
                    <span className={styles.phoneNum}>{contact.phone}</span>
                </div>
            </div>

            {/* Tab select bar */}
            <div className={styles.tabBar}>
                <button
                    className={`${styles.tabBtn} ${activeTab === 'calls' ? styles.activeTab : ''}`}
                    onClick={() => {
                        // Also closes the delete-calls dialog if it was open,
                        // for the same reason Back does (see exitSelection).
                        exitSelection();
                        setActiveTab('calls');
                    }}
                >
                    Calls
                </button>
                <button
                    className={`${styles.tabBtn} ${activeTab === 'tasks' ? styles.activeTab : ''}`}
                    onClick={() => {
                        exitSelection();
                        setActiveTab('tasks');
                    }}
                >
                    Tasks
                </button>
            </div>

            <main className={styles.mainContent}>
                {activeTab === 'calls' ? (
                    <div className={styles.tabContentList}>
                        {callDeletion.error && <p className={styles.errorMessage}>{callDeletion.error}</p>}
                        {calls.length > 0 ? (
                            calls.map((call) => {
                                const isExpanded = expandedCallIds.has(call._id);
                                return (
                                    <div
                                        key={call._id}
                                        ref={(node) => {
                                            if (node) callNodes.current.set(call._id, node);
                                            else callNodes.current.delete(call._id);
                                        }}
                                        className={`${styles.callCard} ${styles.selectableCard} ${callSelection.isSelected(call._id) ? styles.selectedCard : ''} ${highlightedCallId === call._id ? styles.highlightedCard : ''}`}
                                        {...callSelection.getItemProps(call._id)}
                                    >
                                        <div className={styles.callHeader}>
                                            <div className={styles.callTypeSection}>
                                                <div className={styles.callIconBox}>
                                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#2563eb" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                                                        <path d="m3 21 1.9-1.9a2.36 2.36 0 0 0 0-3.3l-1.44-1.44a2.36 2.36 0 0 1 0-3.32l.83-.83a2.36 2.36 0 0 1 3.32 0l1.44 1.44a2.36 2.36 0 0 0 3.3 0L14.5 9.5a2.36 2.36 0 0 0 0-3.3l-1.44-1.44a2.36 2.36 0 0 1 0-3.32l.83-.83a2.36 2.36 0 0 1 3.32 0l1.44 1.44a2.36 2.36 0 0 0 3.3 0l1.9 1.9" />
                                                    </svg>
                                                </div>
                                                <div className={styles.callMeta}>
                                                    <h3 className={styles.callTitle}>
                                                        {formatCallType(call.callType)}
                                                    </h3>
                                                    <p className={styles.callTime}>{formatTime(call.callDateTime)}{formatDuration(call.callLength) ? ` • ${formatDuration(call.callLength)}` : ''}</p>
                                                </div>
                                            </div>
                                            <span className={styles.callDate}>{getRelativeDay(call.callDateTime)}</span>
                                        </div>

                                        <p className={styles.callSummary}>{call.callSummary || 'No summary available.'}</p>

                                        <button
                                            onClick={(e) => {
                                                if (callSelection.isSelecting) return;
                                                e.stopPropagation();
                                                toggleCallTranscript(call._id);
                                            }}
                                            className={styles.viewTranscriptBtn}
                                        >
                                            {isExpanded ? 'Hide transcript' : 'View transcript'} &rarr;
                                        </button>

                                        {isExpanded && (
                                            <div className={styles.expandedTranscriptBox}>
                                                {renderTranscript(call.fullTranscript)}
                                            </div>
                                        )}
                                    </div>
                                );
                            })
                        ) : (
                            <p className={styles.statusMessage}>No calls found for this contact.</p>
                        )}
                    </div>
                ) : (
                    <div className={styles.tabContentList}>
                        {/* Open Tasks List */}
                        {openTasks.length > 0 ? (
                            openTasks.map(task => (
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
                        ) : doneTasks.length === 0 ? (
                            <p className={styles.statusMessage}>No tasks found for this contact.</p>
                        ) : null}

                        {/* Completed Tasks section matching Done (2) screenshot */}
                        {doneTasks.length > 0 && (
                            <div className={styles.doneSection}>
                                <h3 className={styles.doneHeader}>Done ({doneTasks.length})</h3>
                                <div className={styles.doneTasksList}>
                                    {doneTasks.map(task => (
                                        <div key={task._id} className={styles.taskCard}>
                                            <label className={styles.checkboxContainer}>
                                                <input
                                                    type="checkbox"
                                                    checked={task.completed}
                                                    onChange={() => toggleTask(task._id)}
                                                />
                                                <span className={`${styles.checkmark} ${styles.checkedMark}`}></span>
                                            </label>
                                            <div className={styles.taskContent}>
                                                <div className={styles.taskHeader}>
                                                    <h3 className={`${styles.taskTitle} ${styles.completedTitle}`}>{task.title}</h3>
                                                    <span className={`${styles.priorityTag} ${styles[task.priority.toLowerCase()]}`}>{task.priority}</span>
                                                </div>
                                                <div className={styles.taskFooter}>
                                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={styles.calendarIcon}>
                                                        <rect width="18" height="18" x="3" y="4" rx="2" ry="2" />
                                                        <line x1="16" x2="16" y1="2" y2="6" />
                                                        <line x1="8" x2="8" y1="2" y2="6" />
                                                        <line x1="3" x2="21" y1="10" y2="10" />
                                                    </svg>
                                                    <span className={styles.dueDate}>
                                                        {formatDateDisplay(task.dueDate)}
                                                    </span>
                                                </div>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </main>

            {/* Task edit modal inside contact details page */}
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

            {isDeleteCallsOpen && (
                <ConfirmDialog
                    title={`Delete ${callSelection.count} ${callSelection.count === 1 ? 'call' : 'calls'}?`}
                    message="This can't be undone."
                    busy={callDeletion.isDeleting}
                    onCancel={() => setIsDeleteCallsOpen(false)}
                    onConfirm={handleDeleteSelectedCalls}
                />
            )}

            {isDeleteContactOpen && (
                <ConfirmDialog
                    title={`Delete ${contact.name}?`}
                    message={contactDeleteMessage()}
                    busy={isDeletingContact}
                    onCancel={() => setIsDeleteContactOpen(false)}
                    onConfirm={handleDeleteContact}
                />
            )}

            <BottomNav />
        </div>
    );
};

export default ContactDetailsPage;
