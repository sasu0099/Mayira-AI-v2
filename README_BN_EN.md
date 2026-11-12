# Mayira — Latest Android Project (v1.3.0)

Project/app name: **Mayira**. There is no “Delta” suffix.

## Included in this source package
- Custom assistant name and Gemini API key setting
- Gemini 2.5 Flash chat connection
- Coding-help, chat-drafting and project-idea shortcuts
- Premium dark dashboard with animated orb
- Local chat memory with JSON export/restore
- Memory schema version and migration hook
- Voice input, text-to-speech and voice mute
- Dark/light theme
- Android screen-capture permission prompt and stop control
- GitHub latest-release checker
- GitHub Actions workflow for APK build

## Not fully implemented yet
- Screen-capture permission is requested, but live screen frames are not streamed to Gemini.
- Project ideas are available on demand; scheduled proactive suggestions are not implemented.
- Update checker opens the latest GitHub release page; Android installation still requires user confirmation.
- Workflow creates a debug APK, not a production release signed with a persistent private keystore.

## GitHub upload
1. Extract this ZIP.
2. Create a GitHub repository named `Mayira`.
3. Choose **Add file → Upload files**.
4. Upload all extracted files/folders, including `.github` and `app`.
5. Commit to `main`.

## Build APK
1. Repository → **Actions**.
2. Choose **Build Mayira Android APK** → **Run workflow**.
3. After success, download the **Mayira-APK** artifact.
4. Extract the artifact ZIP and install the APK.

## Memory and updates
Keep `applicationId` as `com.mayira.assistant`. Do not uninstall the app or clear its data. In-place updates require the same signing certificate as the installed APK. This workflow builds a debug APK; configure one persistent release keystore for production updates. Export a JSON memory backup before major updates. API keys are excluded from backups.
