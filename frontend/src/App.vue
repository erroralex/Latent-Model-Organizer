<script setup>
import { ref } from 'vue';

const sourceFolder = ref('');
const targetFolder = ref('');
const statusMessage = ref('Ready.');
const isProcessing = ref(false);

const pickSource = async () => {
  const folder = await window.electronAPI.selectFolder();
  if (folder) sourceFolder.value = folder;
};

const pickTarget = async () => {
  const folder = await window.electronAPI.selectFolder();
  if (folder) targetFolder.value = folder;
};

const executeOrganization = async () => {
  if (!sourceFolder.value || !targetFolder.value) {
    statusMessage.value = "⚠️ Please select both source and target directories.";
    return;
  }

  isProcessing.value = true;
  statusMessage.value = "⏳ Scanning and organizing models... Please wait.";

  try {
    // Call the Java 21 Backend
    const response = await fetch('http://localhost:8080/api/organize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceDirectory: sourceFolder.value,
        targetDirectory: targetFolder.value
      })
    });

    if (!response.ok) {
      throw new Error(`Server responded with status: ${response.status}`);
    }

    statusMessage.value = "✅ Organization Complete! Check your target folder.";
  } catch (error) {
    console.error("Organization failed:", error);
    statusMessage.value = "❌ Error: Could not connect to the backend. Is the Java server running?";
  } finally {
    isProcessing.value = false;
  }
};
</script>

<template>
  <main class="container">
    <h1>Latent Model Organizer</h1>

    <div class="card">
      <div class="form-group">
        <label>Source Directory (Unorganized Models):</label>
        <div class="input-row">
          <input type="text" readonly :value="sourceFolder" placeholder="No folder selected..." />
          <button @click="pickSource" :disabled="isProcessing">Browse</button>
        </div>
      </div>

      <div class="form-group">
        <label>Target Directory (Organized Output):</label>
        <div class="input-row">
          <input type="text" readonly :value="targetFolder" placeholder="No folder selected..." />
          <button @click="pickTarget" :disabled="isProcessing">Browse</button>
        </div>
      </div>

      <button class="primary-btn" @click="executeOrganization" :disabled="isProcessing">
        {{ isProcessing ? 'Organizing...' : 'Start Organization' }}
      </button>
    </div>

    <div class="status-bar" :class="{ 'error': statusMessage.includes('❌') }">
      {{ statusMessage }}
    </div>
  </main>
</template>

<style scoped>
/* Basic styling for rapid prototyping */
.container { font-family: system-ui, sans-serif; max-width: 600px; margin: 40px auto; padding: 20px; }
h1 { color: #2c3e50; text-align: center; margin-bottom: 30px; }
.card { background: #f8f9fa; padding: 25px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
.form-group { margin-bottom: 20px; }
label { display: block; font-weight: bold; margin-bottom: 8px; font-size: 0.9em; }
.input-row { display: flex; gap: 10px; }
input { flex: 1; padding: 10px; border: 1px solid #ccc; border-radius: 4px; background: #fff; }
button { padding: 10px 15px; border: none; border-radius: 4px; background: #4a5568; color: white; cursor: pointer; font-weight: bold; }
button:hover:not(:disabled) { background: #2d3748; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.primary-btn { width: 100%; background: #42b883; margin-top: 10px; font-size: 1.1em; }
.primary-btn:hover:not(:disabled) { background: #33a06f; }
.status-bar { margin-top: 20px; padding: 15px; text-align: center; font-weight: bold; border-radius: 4px; background: #e2e8f0; }
.error { background: #fed7d7; color: #c53030; }
</style>