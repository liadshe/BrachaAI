## 1.File Detection: 
A background service (CallMonitorService) watches for new .m4a files in the device's call recordings folder.
## 2. Filename Parsing: 
The app extracts the contact name, date, and time directly from the filename (e.g., נעה חדד_260415_165702.m4a).

## 3. Audio Normalization: 
Using FFmpeg (ffmpeg-kit-audio), the app converts the raw audio into a standard 128kbps MP3 file to ensure compatibility and reduce upload size.

##4. Speech-to-Text (Whisper): 
The MP3 is sent to the OpenAI Whisper-1 model for high-accuracy transcription.

## 5. AI Text Correction (GPT-4o): 
The raw text is processed by GPT-4o to correct grammar, spelling, and formatting (especially useful for Hebrew/English mixed calls).

## 6. Backend Sync: 
The final transcript and metadata are sent via a POST request to a Node.js server (http://10.0.2.2:3000/api/calls).

## 7. Cleanup: 
Temporary MP3 files are deleted from the device cache to save storage space.