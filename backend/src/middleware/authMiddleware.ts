import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import { JWT_SECRET } from '../config/jwt';

export interface AuthRequest extends Request {
    user?: {
        id: string;
        name?: string;
        email?: string;
        phoneNumber?: string;
        profilePicture?: string;
        businessDescription?: string;
    };
}

export const protect = (req: AuthRequest, res: Response, next: NextFunction) => {
    const authHeader = req.headers.authorization;
    
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ message: 'No token, authorization denied' });
    }

    const token = authHeader.split(' ')[1];

    try {
        const decoded = jwt.verify(token, JWT_SECRET) as {
            id: string;
            name?: string;
            email?: string;
            phoneNumber?: string;
            profilePicture?: string;
            businessDescription?: string;
        };
        req.user = {
            id: decoded.id,
            name: decoded.name,
            email: decoded.email,
            phoneNumber: decoded.phoneNumber,
            profilePicture: decoded.profilePicture,
            businessDescription: decoded.businessDescription,
        };
        next();
    } catch (error) {
        console.error('Auth middleware verification failed:', error);
        res.status(401).json({ message: 'Token is not valid' });
    }
};
