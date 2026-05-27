# Bracha AI - Frontend

Modern React + TypeScript PWA for Bracha AI call management and task tracking system.

## Features

- 📱 Progressive Web App (PWA) - installable on mobile devices
- 🎨 Modern, responsive UI built with React 18 and TypeScript
- ⚡ Fast development with Vite
- 🔒 Secure authentication and API integration
- 📞 Call history and management
- ✓ Task creation and tracking
- 👥 Contact management
- 📊 Dashboard with insights

## Tech Stack

- **Frontend**: React 18, TypeScript
- **Bundler**: Vite
- **Routing**: React Router v6
- **Styling**: CSS Modules + Global CSS
- **HTTP Client**: Native Fetch API
- **PWA**: Service Workers, Web App Manifest

## Project Structure

```
frontend/
├── public/
│   ├── index.html
│   ├── manifest.json          # PWA manifest
│   └── logo.jpg               # Bracha AI logo
├── src/
│   ├── components/            # Reusable UI components
│   ├── pages/                 # Page components
│   │   └── StartPage.tsx      # Landing page
│   ├── services/              # API and utility services
│   │   └── api.ts             # Backend API client
│   ├── context/               # React Context for state
│   ├── utils/                 # Utility functions
│   ├── styles/                # Global styles
│   │   └── global.css         # CSS variables and base styles
│   ├── App.tsx                # Main app component
│   └── index.tsx              # App entry point
├── vite.config.ts
├── tsconfig.json
├── package.json
└── README.md
```

## Getting Started

### Prerequisites

- Node.js 16+ and npm/yarn
- Running backend server at `http://localhost:3000`

### Installation

```bash
cd frontend
npm install
```

### Development

```bash
npm run dev
```

The app will open automatically at `http://localhost:5173`

### Build for Production

```bash
npm run build
```

### Preview Production Build

```bash
npm run preview
```

## Environment Variables

Create `.env.development` for local development:

```env
VITE_API_URL=http://localhost:3000/api
```

Create `.env.production` for production:

```env
VITE_API_URL=https://api.yourdomain.com/api
```

## Pages

### ✅ Start Page (Implemented)
- Landing page with hero section
- Features showcase
- Call-to-action buttons (Get Started, Create Account)
- Modern gradient design with animations
- Fully responsive mobile design

### 🔄 Pages to Build
- **Login Page**: User authentication
- **Signup Page**: New account creation
- **Dashboard**: Overview of recent calls and tasks
- **Calls**: View and manage call history
- **Call Detail**: Full transcript and analysis
- **Tasks**: Create and manage tasks
- **Contacts**: Manage business contacts
- **Settings**: User preferences and profile

## API Integration

The frontend connects to backend at `/api` endpoint. See [Backend Documentation](../backend/README.md) for API specs.

### Key Endpoints

- `POST /api/calls` - Create call record
- `GET /api/calls` - List calls
- `GET /api/tasks` - Get user tasks
- `POST /api/tasks` - Create task
- `GET /api/contacts` - Get contacts
- `POST /api/users/login` - User login
- `POST /api/users/signup` - User signup

## PWA Features

- Installable on home screen
- Works offline (with service worker)
- Fast loading with caching
- Native app-like experience

## Styling

The project uses CSS Modules for component styles and global CSS for theme variables.

### CSS Variables (in `global.css`)

- Colors: `--primary`, `--secondary`, `--success`, `--error`, etc.
- Spacing: `--spacing-xs` to `--spacing-2xl`
- Typography: `--font-size-*` and `--font-family`
- Shadows: `--shadow-sm` to `--shadow-xl`
- Border radius: `--radius-sm` to `--radius-full`

## Performance

- Code splitting with React Router
- Image optimization
- CSS-in-JS optimization
- Service worker caching strategy

## Browser Support

- Chrome/Edge 88+
- Firefox 87+
- Safari 14+
- Mobile browsers (iOS Safari, Chrome Mobile)

## Contributing

1. Create a new branch for your feature
2. Make your changes
3. Test thoroughly
4. Submit a pull request

## License

ISC

---

**Next Steps**: Build the Login/Signup pages and Dashboard component.
