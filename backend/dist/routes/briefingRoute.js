"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const briefingController_1 = require("../controllers/briefingController");
const authMiddleware_1 = require("../middleware/authMiddleware");
const router = (0, express_1.Router)();
router.get('/briefings', authMiddleware_1.protect, briefingController_1.getBriefings);
router.get('/briefings/:contactId', authMiddleware_1.protect, briefingController_1.getBriefingByContactId);
exports.default = router;
