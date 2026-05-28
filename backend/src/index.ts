import express from 'express';
import cors from 'cors';
import mongoose from 'mongoose';
import dotenv from 'dotenv';
import callRoutes from './routes/callRoute';
import authRoutes from './routes/authRoute';
import taskRoutes from './routes/taskRoute';
import clientRoutes from './routes/clientRoute';



// 1. Load the secrets from your .env file
dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

// 2. Middleware
app.use(express.json());
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
app.use('/api', clientRoutes);



// 5. Start the engine
app.listen(PORT, () => {
    console.log(`🚀 Bracha AI Backend is live at http://localhost:${PORT}`);
});