"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const callController_1 = require("../controllers/callController");
const authMiddleware_1 = require("../middleware/authMiddleware");
const router = (0, express_1.Router)();
// This defines the /api/calls endpoint
// Since index.ts uses app.use('/api', callRoutes), 
// this becomes http://localhost:3000/api/calls
router.post('/calls', callController_1.handleIncomingAndroidCall);
router.get('/calls', authMiddleware_1.protect, callController_1.getCalls);
exports.default = router;
