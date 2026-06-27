const state = {
  screen: "home",
  previousScreen: null,
  connection: "disconnected",
  selectedProfile: 0,
  routingMode: "rules",
  profiles: [
    { name: "Frankfurt · Main", protocol: "VLESS", detail: "Reality · Vision · DE", flag: "🇩🇪", latency: 42 },
    { name: "Amsterdam · Edge", protocol: "VMess", detail: "WebSocket · TLS · NL", flag: "🇳🇱", latency: 57 },
    { name: "Helsinki · Secure", protocol: "Trojan", detail: "TCP · TLS · FI", flag: "🇫🇮", latency: 63 },
    { name: "Warsaw · Backup", protocol: "SS", detail: "2022-blake3-aes-128-gcm · PL", flag: "🇵🇱", latency: 71 }
  ],
  switches: {
    ipv6: false,
    autoConnect: true,
    localNetwork: true,
    appFilter: false
  }
};

const screen = document.querySelector("#screen");
const sheet = document.querySelector("#sheet");
const backdrop = document.querySelector("#sheet-backdrop");
const toast = document.querySelector("#toast");
let toastTimer;
let connectTimer;

const profile = () => state.profiles[state.selectedProfile];

const templates = {
  home() {
    const current = profile();
    const connected = state.connection === "connected";
    const connecting = state.connection === "connecting";
    const connectionClass = connected ? "connected" : connecting ? "connecting" : "";
    const title = connected ? "Защищено" : connecting ? "Подключение…" : "Не подключено";
    const eyebrow = connected ? "VPN активен · 00:14:28" : "Состояние подключения";
    return `
      <div class="${connectionClass}">
        <div class="connection-hero">
          <div class="connection-state">
            <span class="eyebrow">${eyebrow}</span>
            <strong>${title}</strong>
          </div>
          <div class="connect-wrap">
            <button class="connect-button" type="button" aria-label="${connected ? "Отключиться" : "Подключиться"}" data-action="toggle-connect">
              <span class="power"></span>
            </button>
          </div>
        </div>
        <button class="profile-card" type="button" data-action="navigate" data-target="profiles">
          <span class="flag">${current.flag}</span>
          <span class="profile-copy">
            <strong>${current.name}</strong>
            <span>${current.protocol} · ${current.detail}</span>
          </span>
          <span class="latency">${current.latency} ms</span>
          <span class="chevron">›</span>
        </button>
        <div class="stats-grid">
          <div class="stat"><span>Загрузка</span><strong>${connected ? "2.8 MB" : "—"}</strong></div>
          <div class="stat"><span>Отправлено</span><strong>${connected ? "640 KB" : "—"}</strong></div>
          <div class="stat"><span>IP</span><strong>${connected ? "91.•••.48" : "—"}</strong></div>
        </div>
      </div>
    `;
  },

  profiles() {
    return `
      <div class="section-head">
        <div>
          <span class="eyebrow">4 сервера</span>
          <h1>Профили</h1>
        </div>
        <button class="text-button" type="button" data-action="latency-test">Проверить</button>
      </div>
      <div class="search-box">
        <input type="search" placeholder="Поиск профиля" data-action="search-profiles">
        <span>⌕</span>
      </div>
      <div class="profile-list" id="profile-list">${renderProfiles(state.profiles)}</div>
      <button class="fab" type="button" aria-label="Добавить профиль" data-action="open-add">+</button>
    `;
  },

  routing() {
    return `
      <span class="eyebrow">Маршрутизация</span>
      <h1>Правила трафика</h1>
      <p class="subtitle">Определите, какой трафик направлять через прокси.</p>
      <div class="mode-selector">
        <button class="${state.routingMode === "global" ? "active" : ""}" data-action="routing-mode" data-mode="global">Весь трафик</button>
        <button class="${state.routingMode === "rules" ? "active" : ""}" data-action="routing-mode" data-mode="rules">По правилам</button>
        <button class="${state.routingMode === "direct" ? "active" : ""}" data-action="routing-mode" data-mode="direct">Напрямую</button>
      </div>
      <div class="card rule-card">
        <div class="rule-head"><strong>Заблокированные ресурсы</strong><span class="switch on" data-action="toggle-visual"></span></div>
        <div class="rule-tags"><span class="tag proxy">PROXY</span><span class="tag">geosite:category-ru</span><span class="tag">geoip:!ru</span></div>
      </div>
      <div class="card rule-card">
        <div class="rule-head"><strong>Локальные ресурсы</strong><span class="switch on" data-action="toggle-visual"></span></div>
        <div class="rule-tags"><span class="tag direct">DIRECT</span><span class="tag">geoip:private</span><span class="tag">geosite:private</span></div>
      </div>
      <div class="card rule-card">
        <div class="rule-head"><strong>Реклама и трекеры</strong><span class="switch" data-action="toggle-visual"></span></div>
        <div class="rule-tags"><span class="tag">BLOCK</span><span class="tag">geosite:category-ads-all</span></div>
      </div>
      <div class="setting-group">
        <div class="setting-list">
          ${settingRow("▦", "Фильтр приложений", "Все приложения используют VPN", "appFilter")}
          ${settingRow("⌂", "Локальная сеть", "Разрешить подключения напрямую", "localNetwork")}
        </div>
      </div>
      <button class="secondary-button full-width" type="button" data-action="toast" data-message="Редактор пользовательских правил">+ Добавить правило</button>
    `;
  },

  settings() {
    return `
      <span class="eyebrow">H2Ray</span>
      <h1>Настройки</h1>
      <div class="setting-group">
        <h2>Подключение</h2>
        <div class="setting-list">
          ${settingRow("◉", "Автоподключение", "После перезапуска устройства", "autoConnect")}
          ${settingRow("6", "IPv6", "Отключён", "ipv6")}
          ${settingLink("DNS", "DNS", "Remote DNS · 1.1.1.1", "dns")}
          ${settingLink("↕", "MTU", "Автоматически · 1500", "mtu")}
        </div>
      </div>
      <div class="setting-group">
        <h2>Приложение</h2>
        <div class="setting-list">
          ${settingLink("▣", "Журнал подключения", "Диагностика Xray-core", "logs")}
          ${settingLink("◒", "Оформление", "Тёмная тема", "appearance")}
          ${settingLink("i", "О приложении", "H2Ray UI Prototype · com.h2ray.app", "about")}
        </div>
      </div>
    `;
  },

  editor() {
    const current = profile();
    return `
      <span class="eyebrow">Редактирование</span>
      <h1>${current.name}</h1>
      <p class="subtitle">Параметры подключения Xray.</p>
      <div class="field">
        <label>Название</label>
        <input value="${current.name}">
      </div>
      <div class="field">
        <label>Протокол</label>
        <select>
          <option ${current.protocol === "VLESS" ? "selected" : ""}>VLESS</option>
          <option ${current.protocol === "VMess" ? "selected" : ""}>VMess</option>
          <option ${current.protocol === "Trojan" ? "selected" : ""}>Trojan</option>
          <option ${current.protocol === "SS" ? "selected" : ""}>Shadowsocks</option>
        </select>
      </div>
      <div class="form-grid">
        <div class="field">
          <label>Адрес</label>
          <input value="de1.example.net">
        </div>
        <div class="field">
          <label>Порт</label>
          <input inputmode="numeric" value="443">
        </div>
      </div>
      <div class="field">
        <label>UUID</label>
        <input value="••••••••-••••-••••-••••-••••••••••••">
      </div>
      <div class="form-grid">
        <div class="field">
          <label>Transport</label>
          <select><option>RAW</option><option>WebSocket</option><option>XHTTP</option><option>gRPC</option></select>
        </div>
        <div class="field">
          <label>Security</label>
          <select><option>REALITY</option><option>TLS</option><option>None</option></select>
        </div>
      </div>
      <div class="field">
        <label>Server name / SNI</label>
        <input value="www.microsoft.com">
      </div>
      <div class="form-actions">
        <button class="danger-button" type="button" data-action="delete-profile">Удалить</button>
        <button class="primary-button" type="button" data-action="save-profile">Сохранить</button>
      </div>
    `;
  },

  logs() {
    return `
      <div class="section-head">
        <div><span class="eyebrow">Диагностика</span><h1>Журнал</h1></div>
        <button class="text-button" data-action="toast" data-message="Журнал очищен">Очистить</button>
      </div>
      <div class="log-box">
        <span class="log-info">14:31:02 [Info]</span> H2Ray service starting<br>
        14:31:02 [Info] Xray-core v26.6.1<br>
        14:31:02 [Info] loading config: memory://h2ray.json<br>
        14:31:02 [Info] transport/internet: listening TCP on 127.0.0.1:10808<br>
        <span class="log-ok">14:31:02 [Info] Xray started</span><br>
        14:31:03 [Info] VPN interface established: h2ray0<br>
        14:31:03 [Info] default route: 0.0.0.0/0<br>
        14:31:03 [Info] DNS route: 1.1.1.1<br>
        14:31:04 [Info] proxy/VLESS: tunneling request to tcp:example.org:443
      </div>
    `;
  }
};

function renderProfiles(profiles) {
  if (!profiles.length) {
    return `<div class="empty-state"><span>⌕</span>Профили не найдены</div>`;
  }
  return profiles.map((item) => {
    const index = state.profiles.indexOf(item);
    return `
      <div class="server-row ${index === state.selectedProfile ? "selected" : ""}" data-action="select-profile" data-index="${index}">
        <span class="protocol-badge">${item.protocol}</span>
        <span class="server-info">
          <strong>${item.flag} ${item.name}</strong>
          <span>${item.detail}</span>
        </span>
        <span class="latency">${item.latency} ms</span>
        <button class="icon-button" type="button" aria-label="Редактировать" data-action="edit-profile" data-index="${index}">⋮</button>
        <span class="radio"></span>
      </div>
    `;
  }).join("");
}

function settingRow(icon, title, detail, key) {
  const enabled = state.switches[key];
  return `
    <div class="setting-row" data-action="toggle-setting" data-key="${key}">
      <span class="setting-icon">${icon}</span>
      <span class="setting-copy"><strong>${title}</strong><span>${detail}</span></span>
      <span class="switch ${enabled ? "on" : ""}"></span>
    </div>
  `;
}

function settingLink(icon, title, detail, target) {
  return `
    <div class="setting-row" data-action="setting-link" data-target="${target}">
      <span class="setting-icon">${icon}</span>
      <span class="setting-copy"><strong>${title}</strong><span>${detail}</span></span>
      <span class="chevron">›</span>
    </div>
  `;
}

function render() {
  screen.innerHTML = templates[state.screen]();
  document.querySelectorAll(".nav-item").forEach((item) => {
    item.classList.toggle("active", item.dataset.target === state.screen);
  });
  const detailScreen = ["editor", "logs"].includes(state.screen);
  document.querySelector(".back-button").classList.toggle("hidden", !detailScreen);
  document.querySelector(".bottom-nav").classList.toggle("hidden", detailScreen);
}

function navigate(target) {
  if (!templates[target]) return;
  state.previousScreen = state.screen;
  state.screen = target;
  render();
}

function showToast(message) {
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.classList.remove("hidden");
  toastTimer = setTimeout(() => toast.classList.add("hidden"), 2200);
}

function openSheet(content) {
  sheet.innerHTML = `<div class="sheet-handle"></div>${content}`;
  sheet.classList.remove("hidden");
  backdrop.classList.remove("hidden");
}

function closeSheet() {
  sheet.classList.add("hidden");
  backdrop.classList.add("hidden");
}

function showAddSheet(mode) {
  if (mode === "qr") {
    openSheet(`
      <h2>Сканирование QR</h2>
      <p class="subtitle">Наведите камеру на QR-код конфигурации.</p>
      <div class="scanner"></div>
      <button class="secondary-button full-width" data-action="toast" data-message="Выбор изображения из галереи">Выбрать из галереи</button>
    `);
    return;
  }
  openSheet(`
    <h2>Добавить профиль</h2>
    <p class="subtitle">Импортируйте готовую конфигурацию или заполните параметры вручную.</p>
    <div class="action-grid">
      <button class="action-card" data-action="import-method" data-method="clipboard"><span>▣</span><strong>Из буфера</strong></button>
      <button class="action-card" data-action="open-add" data-mode="qr"><span>▦</span><strong>QR-код</strong></button>
      <button class="action-card" data-action="import-method" data-method="subscription"><span>↻</span><strong>Подписка</strong></button>
      <button class="action-card" data-action="import-method" data-method="manual"><span>＋</span><strong>Вручную</strong></button>
      <button class="action-card" data-action="import-method" data-method="json"><span>{ }</span><strong>Xray JSON</strong></button>
      <button class="action-card" data-action="import-method" data-method="file"><span>⇧</span><strong>Из файла</strong></button>
    </div>
  `);
}

function showImportForm(method) {
  const definitions = {
    clipboard: ["Импорт из буфера", "vless://, vmess://, trojan:// или ss://", "Вставить и проверить"],
    subscription: ["Добавить подписку", "https://example.com/subscription", "Загрузить"],
    json: ["Импорт Xray JSON", "{\n  \"outbounds\": []\n}", "Проверить JSON"],
    file: ["Импорт файла", "Выберите JSON или TXT-файл", "Выбрать файл"]
  };
  if (method === "manual") {
    closeSheet();
    navigate("editor");
    return;
  }
  const [title, placeholder, action] = definitions[method];
  openSheet(`
    <h2>${title}</h2>
    <div class="field">
      <label>Конфигурация</label>
      <textarea placeholder="${placeholder}"></textarea>
    </div>
    <button class="primary-button full-width" data-action="complete-import">${action}</button>
  `);
}

document.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (!target) return;
  const action = target.dataset.action;

  if (action === "navigate") navigate(target.dataset.target);
  if (action === "back") navigate(state.previousScreen || "settings");
  if (action === "toast") showToast(target.dataset.message);
  if (action === "close-sheet") closeSheet();
  if (action === "open-add") showAddSheet(target.dataset.mode);
  if (action === "import-method") showImportForm(target.dataset.method);

  if (action === "toggle-connect") {
    clearTimeout(connectTimer);
    if (state.connection === "connected") {
      state.connection = "disconnected";
      showToast("VPN отключён");
      render();
    } else if (state.connection !== "connecting") {
      state.connection = "connecting";
      render();
      connectTimer = setTimeout(() => {
        state.connection = "connected";
        render();
        showToast("Защищённое подключение установлено");
      }, 1100);
    }
  }

  if (action === "select-profile") {
    state.selectedProfile = Number(target.dataset.index);
    render();
    showToast(`Выбран: ${profile().name}`);
  }

  if (action === "edit-profile") {
    event.stopPropagation();
    state.selectedProfile = Number(target.dataset.index);
    navigate("editor");
  }

  if (action === "latency-test") {
    showToast("Проверка задержки…");
    setTimeout(() => {
      state.profiles.forEach((item, index) => item.latency = [39, 54, 61, 76][index]);
      render();
      showToast("Задержка обновлена");
    }, 900);
  }

  if (action === "routing-mode") {
    state.routingMode = target.dataset.mode;
    render();
  }

  if (action === "toggle-setting") {
    const key = target.dataset.key;
    state.switches[key] = !state.switches[key];
    render();
  }

  if (action === "toggle-visual") target.classList.toggle("on");

  if (action === "setting-link") {
    if (target.dataset.target === "logs") navigate("logs");
    else showToast(`Раздел «${target.querySelector("strong").textContent}»`);
  }

  if (action === "save-profile") {
    navigate("profiles");
    showToast("Профиль сохранён");
  }

  if (action === "delete-profile") showToast("Удаление требует подтверждения");

  if (action === "complete-import") {
    closeSheet();
    showToast("Конфигурация распознана");
  }
});

document.addEventListener("input", (event) => {
  if (event.target.dataset.action !== "search-profiles") return;
  const query = event.target.value.trim().toLowerCase();
  const filtered = state.profiles.filter((item) =>
    `${item.name} ${item.protocol} ${item.detail}`.toLowerCase().includes(query)
  );
  document.querySelector("#profile-list").innerHTML = renderProfiles(filtered);
});

render();
