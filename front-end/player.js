let hls;
let video = document.getElementById('video');
let qualitySelect = document.getElementById('qualitySelect');
let statusDiv = document.getElementById('status');
let loadBtn = document.getElementById('loadBtn');
let videoInfo = document.getElementById('videoInfo');
let mediaErrorRecoveryCount = 0;

function showStatus(message, type = 'loading') {
    statusDiv.textContent = message;
    statusDiv.className = `status ${type}`;
    statusDiv.classList.remove('hidden');
}

function hideStatus() {
    statusDiv.classList.add('hidden');
}

async function loadVideo() {
    let videoId = document.getElementById('videoId').value.trim();
    if (!videoId) {
        showStatus("Por favor, digite um ID de vídeo!", "error");
        return;
    }

    const masterUrl = `http://localhost:8080/api/v1/stream/${videoId}/master.m3u8`;
    
    showStatus("Carregando vídeo...", "loading");
    loadBtn.disabled = true;
    video.classList.add('hidden');
    qualitySelect.classList.add('hidden');
    videoInfo.innerHTML = '';
    
    // Primeiro, vamos verificar se o arquivo existe
    try {
        const response = await fetch(masterUrl);
        if (!response.ok) {
            throw new Error(`Vídeo não encontrado (${response.status})`);
        }
    } catch (error) {
        showStatus(`Erro ao carregar vídeo: ${error.message}`, "error");
        loadBtn.disabled = false;
        return;
    }

    if (hls) {
        hls.destroy();
        hls = null;
    }

    if (Hls.isSupported()) {
        hls = new Hls({
            debug: false,
            startLevel: -1,
            capLevelToPlayerSize: true,
            maxBufferSize: 30 * 1000 * 1000,
            maxBufferLength: 20,
            enableWorker: true,
            lowLatencyMode: false
        });

        hls.on(Hls.Events.LEVEL_SWITCHING, function(event, data) {
            console.log(`Mudando qualidade - De: ${hls.levels[hls.currentLevel]?.height}p Para: ${hls.levels[data.level]?.height}p`);
            hls.nextLoadLevel = data.level;
        });

        hls.on(Hls.Events.LEVEL_SWITCHED, function(event, data) {
            console.log(`Qualidade alterada para: ${hls.levels[data.level].height}p`);
            qualitySelect.value = data.level;
            updateVideoInfo();
        });

        hls.on(Hls.Events.ERROR, function(event, data) {
            console.error('Erro HLS:', data);
            if (data.fatal) {
                switch (data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        showStatus("Erro de rede. Tente novamente.", "error");
                        hls.destroy();
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        showStatus("Erro de mídia. Seu navegador não suporta este vídeo.", "error");
                        hls.destroy();
                        break;
                    default:
                        showStatus("Erro fatal no streaming.", "error");
                        hls.destroy();
                        break;
                }
            }
        });

        hls.loadSource(masterUrl);
        hls.attachMedia(video);

        hls.on(Hls.Events.MANIFEST_PARSED, function() {
            mediaErrorRecoveryCount = 0;
            qualitySelect.innerHTML = '';
            let autoOption = document.createElement('option');
            autoOption.value = '-1';
            autoOption.text = 'Auto';
            qualitySelect.appendChild(autoOption);
            hls.levels.forEach((level, index) => {
                let option = document.createElement('option');
                option.value = index;
                option.text = `${level.height}p`;
                qualitySelect.appendChild(option);
            });
            qualitySelect.value = '-1';
            qualitySelect.onchange = function() {
                let selectedLevel = parseInt(this.value);
                if (selectedLevel === -1) {
                    hls.currentLevel = -1;
                    hls.loadLevel = -1;
                    hls.autoLevelEnabled = true;
                } else {
                    hls.nextLoadLevel = selectedLevel;
                    hls.currentLevel = selectedLevel;
                    hls.autoLevelEnabled = false;
                }
                updateVideoInfo();
            };
            loadBtn.disabled = false;
            video.classList.remove('hidden');
            qualitySelect.classList.remove('hidden');
            updateVideoInfo();
            video.play().catch(() => {
                showStatus("Clique no vídeo para reproduzir.", "loading");
            });
        });
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = masterUrl;
        video.classList.remove('hidden');
        loadBtn.disabled = false;
        showStatus("Usando player nativo do Safari", "success");
        setTimeout(hideStatus, 3000);
        
        video.addEventListener('loadedmetadata', function() {
            video.play().catch(e => {
                console.log('Autoplay bloqueado, clique para reproduzir');
            });
        });
    } else {
        showStatus("HLS não é suportado neste navegador.", "error");
        loadBtn.disabled = false;
    }
}

function updateVideoInfo() {
    if (!hls || !hls.levels) return;
    
    const currentLevel = hls.currentLevel;
    let info = `<strong>Status:</strong> `;
    
    if (currentLevel === -1) {
        info += `Automático | `;
        if (hls.levels[hls.loadLevel]) {
            info += `Atual: ${hls.levels[hls.loadLevel].height}p`;
        }
    } else {
        info += `Manual: ${hls.levels[currentLevel].height}p`;
    }
    
    info += `<br><strong>Qualidades disponíveis:</strong> ${hls.levels.map(l => l.height + 'p').join(', ')}`;
    
    videoInfo.innerHTML = info;
}

// Adicionar listener para quando o documento carregar
document.addEventListener('DOMContentLoaded', function() {
    // Adicionar exemplo de ID de vídeo se existir
    const videoInput = document.getElementById('videoId');
    if (videoInput.value === '') {
        videoInput.placeholder = 'Ex: 2724a02c-f275-49d2-8389-e76bfeacd4c6';
    }
    
    // Permitir carregar vídeo com Enter
    videoInput.addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            loadVideo();
        }
    });
});