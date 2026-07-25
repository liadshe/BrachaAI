"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.JWT_SECRET = void 0;
const dotenv_1 = __importDefault(require("dotenv"));
// Loaded here rather than relying on index.ts: this module is pulled in by the
// route imports, which run before index.ts's own dotenv.config() call.
dotenv_1.default.config();
const secret = process.env.JWT_SECRET;
if (!secret) {
    console.error('❌ JWT_SECRET is not set. Refusing to start with an insecure default.');
    process.exit(1);
}
exports.JWT_SECRET = secret;
