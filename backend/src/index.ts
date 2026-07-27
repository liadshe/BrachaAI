import express from 'express';
import cors from 'cors';
import mongoose from 'mongoose';
import dotenv from 'dotenv';
import callRoutes from './routes/callRoute';
import authRoutes from './routes/authRoute';
import taskRoutes from './routes/taskRoute';
import contactRoutes from './routes/contactRoute';




// 1. Load the secrets from your .env file
dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

// 2. Middleware
// Default 100kb limit truncates real call transcripts (a ~1hr Hebrew call is ~2 bytes/char
// and comfortably exceeds 100kb), which the client treats as a permanent 413 rejection and
// deletes on-device. Raise it so long transcripts aren't silently destroyed.
app.use(express.json({ limit: '5mb' }));
app.use(cors());

// 3. Connect to MongoDB
const MONGO_URI = process.env.DATABASE_URL || "mongodb://localhost:27017/brachaai";

mongoose.connect(MONGO_URI)
    .then(() => console.log("🍃 Connected to MongoDB Successfully"))
    .catch(err => {
        console.error("❌ MongoDB connection error:", err);
        process.exit(1); // Stop the server if the database isn't working
    });

// 4. Routes
// This tells Express: any request starting with /api should look in callRoutes
app.use('/api', callRoutes);
app.use('/api/auth', authRoutes);
app.use('/api', taskRoutes);
app.use('/api', contactRoutes);



// 5. Start the engine
app.listen(PORT, () => {
    console.log(`🚀 Bracha AI Backend is live at http://localhost:${PORT}`);
});