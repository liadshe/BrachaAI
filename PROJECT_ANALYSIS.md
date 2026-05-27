## 1. Android Application 

### Overview
- **Language**: Kotlin

### Core Pipeline
1. **CallMonitorService** watches for audio 

2. **AudioProcessor** processes each audio file:
   - Parses filename (format: `ContactName_YYMMDD_HHMMSS.ext`)
   - Converts to MP3 (128k, 44100Hz, stereo)
   - Sends to OpenAI Whisper API for Hebrew transcription
   - POSTs result to backend

3. **WhisperApiClient** handles Whisper API communication

4. **MainActivity** provides Compose UI with permissions handling

### Key Classes
- `CallMonitorService.kt` - Foreground service with FileObserver
- `AudioProcessor.kt` - Main processing orchestrator
- `WhisperApiClient.kt` - OpenAI Whisper integration
- `FilenameParser.kt` - Filename parsing logic
- `MainActivity.kt` - Jetpack Compose UI

---

## 2. Backend System 

### Overview
- **Language**: TypeScript
- **Database**: MongoDB (Mongoose ODM)

### Architecture
```
Backend (Express.js)
├── API Endpoint: POST /api/calls
├── Call Processing
│   ├── Parse transcription from Android
│   ├── Get/Create contact
│   ├── Save call record
│   ├── AI analysis (GPT-4o)
│   └── Create tasks
└── Database (MongoDB)
    ├── Users
    ├── Contacts
    ├── Calls
    └── Tasks
```

### Database Schemas

**User**
- name: string (required)
- email: string (required, unique)
- phone: string (required)
- avatar: string (optional)
- permissions: string[] (default: ['standard'])

**Contact**
- userId: ObjectId (required, ref User)
- name: string (default: 'unknown')
- phone: string (required)
- isVip: boolean (default: false)

**Call**
- userId: ObjectId (required, ref User)
- contactId: ObjectId (required, ref Contact)
- fullTranscript: string (required)
- callSummary: string (AI-generated)
- callDateTime: Date (default: now)
- callLength: number (in seconds)

**Task**
- userId: ObjectId (required, ref User)
- contactId: ObjectId (required, ref Contact)
- title: string (required)
- description: string
- priority: enum['LOW', 'MEDIUM', 'HIGH'] (default: 'LOW')
- status: enum['todo', 'in-progress', 'done'] (default: 'todo')

### AI Analysis Rules

**Priority Classification**
- **HIGH**: Urgent/time-sensitive, near deadline, blocks important work
  - Example: "Send contract today", "Call client before 5pm"
- **MEDIUM**: Important but not urgent, clear commitment
  - Example: "Schedule follow-up meeting", "Prepare presentation"
- **LOW**: Nice-to-do, vague, or informational
  - Example: "Look into pricing", "Check if report ready"


## 3. Frontend UI Application 

### Goal
Build a React PWA 