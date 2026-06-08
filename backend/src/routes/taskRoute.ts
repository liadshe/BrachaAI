import { Router } from 'express';
import { getTasks, createTask, updateTask, getTasksSummary } from '../controllers/taskController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.get('/tasks', protect, getTasks);
router.get('/tasks/summary', protect, getTasksSummary);
router.post('/tasks', protect, createTask);
router.patch('/tasks/:id', protect, updateTask);

export default router;

