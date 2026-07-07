"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.createTasksFromAi = void 0;
const Task_1 = __importDefault(require("../models/Task"));
const createTasksFromAi = async (userId, contactId, tasks) => {
    const taskPromises = tasks.map(item => {
        return Task_1.default.create({
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
exports.createTasksFromAi = createTasksFromAi;
