# Contributing to Latent Model Organizer

We welcome contributions! Please follow these guidelines.

## Development Setup

1.  **Prerequisites:**
    *   Java 21+ (JDK)
    *   Node.js 20+ & npm
    *   Maven

2.  **Backend:**
    *   Open the project in your IDE (e.g., IntelliJ IDEA).
    *   Navigate to `backend/`.
    *   Run `mvn clean install` to download dependencies.
    *   Run the `main` method in `OrganizerApplication.java` to start the server.

3.  **Frontend:**
    *   Navigate to `frontend/`.
    *   Run `npm install`.
    *   Run `npm run dev` to start the Vite development server.

4.  **Electron:**
    *   Ensure the backend server is running from your IDE.
    *   Ensure the frontend dev server is running.
    *   Navigate to `electron/`.
    *   Run `npm install`.
    *   Run `npm start` to launch the desktop application.

## Code Style

*   **Java:** Follow standard Java conventions. Use 4 spaces for indentation.
*   **Vue/JS:** Use 2 spaces for indentation. Follow Vue 3 Composition API best practices.

## Pull Requests

*   Create a feature branch from `main`.
*   Submit a PR with a clear description of changes.
