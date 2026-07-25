"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const cors_1 = __importDefault(require("cors"));
const mongoose_1 = __importDefault(require("mongoose"));
const dotenv_1 = __importDefault(require("dotenv"));
const callRoute_1 = __importDefault(require("./routes/callRoute"));
const authRoute_1 = __importDefault(require("./routes/authRoute"));
const taskRoute_1 = __importDefault(require("./routes/taskRoute"));
const contactRoute_1 = __importDefault(require("./routes/contactRoute"));
// 1. Load the secrets from your .env file
dotenv_1.default.config();
const app = (0, express_1.default)();
const PORT = process.env.PORT || 3000;
// 2. Middleware
// Default 100kb limit truncates real call transcripts (a ~1hr Hebrew call is ~2 bytes/char
// and comfortably exceeds 100kb), which the client treats as a permanent 413 rejection and
// deletes on-device. Raise it so long transcripts aren't silently destroyed.
app.use(express_1.default.json({ limit: '5mb' }));
app.use((0, cors_1.default)());
// 3. Connect to MongoDB
const MONGO_URI = process.env.DATABASE_URL || "mongodb://localhost:27017/brachaai";
mongoose_1.default.connect(MONGO_URI)
    .then(() => console.log("🍃 Connected to MongoDB Successfully"))
    .catch(err => {
    console.error("❌ MongoDB connection error:", err);
    process.exit(1); // Stop the server if the database isn't working
});
// 4. Routes
// This tells Express: any request starting with /api should look in callRoutes
app.use('/api', callRoute_1.default);
app.use('/api/auth', authRoute_1.default);
app.use('/api', taskRoute_1.default);
app.use('/api', contactRoute_1.default);
// 5. Start the engine
app.listen(PORT, () => {
    console.log(`🚀 Bracha AI Backend is live at http://localhost:${PORT}`);
});
