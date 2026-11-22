-- Splint lab client fixture. Development only: never shipped with Splint.
-- Joins the lab's loopback server, creates a random character, then executes
-- sequence-numbered commands from Lua/splintlab-client-cmd.txt and prints [SPLAB] lines.
-- The client's only job is to be a real connected player, so the server keeps the
-- surrounding chunks loaded and simulated exactly as it would for a human.

SplintLab = SplintLab or { lastSeq = 0, nextPoll = 0 }
local Lab = SplintLab

local function say(...)
    local parts = {}
    for i = 1, select("#", ...) do parts[#parts + 1] = tostring(select(i, ...)) end
    print("[SPLAB] " .. table.concat(parts, " "))
end

local function readLines(name)
    local reader = getFileReader(name, false)
    if not reader then return nil end
    local lines = {}
    while true do
        local line = reader:readLine()
        if not line then break end
        lines[#lines + 1] = line
    end
    reader:close()
    return lines
end

local connecting = false
Events.OnMainMenuEnter.Add(function()
    if connecting then return end
    local lines = readLines("splintlab-connect.txt")
    if not lines or #lines < 4 then return end
    connecting = true
    say("CONNECTING", lines[1], lines[2])
    ConnectToServer.instance:connect(MainScreen.instance.bottomPanel, "", lines[3], lines[4],
        lines[1], lines[1], lines[2], "", false, true, 1)
end)

local lastScreen
Events.OnFETick.Add(function()
    local main = MainScreen.instance
    if not main then return end
    local screen
    if MapSpawnSelect.instance and MapSpawnSelect.instance:isVisible() then
        screen = "spawn"
        MapSpawnSelect.instance:useDefaultSpawnRegion()
        MapSpawnSelect.instance:onOptionMouseDown({ internal = "NEXT" })
    elseif WorldSelect.instance and WorldSelect.instance:isVisible() then
        screen = "world"
        WorldSelect.instance:onOptionMouseDown({ internal = "NEXT" })
    elseif main.charCreationProfession and main.charCreationProfession:isVisible() then
        screen = "profession"
        main.charCreationProfession:onOptionMouseDown({ internal = "NEXT" })
    elseif main.charCreationMain and main.charCreationMain:isVisible() then
        screen = "character"
        main.charCreationMain:onOptionMouseDown({ internal = "RANDOM" })
        main.charCreationMain:onOptionMouseDown({ internal = "NEXT" })
    end
    if screen and screen ~= lastScreen then say("SCREEN", screen) end
    lastScreen = screen or lastScreen
end)

function Lab.player() return getSpecificPlayer(0) end

function Lab.status()
    local p = Lab.player()
    if not p then return "noplayer" end
    local stats = p:getStats()
    return string.format("x=%.1f y=%.1f z=%d hunger=%.4f thirst=%.4f endurance=%.4f",
        p:getX(), p:getY(), p:getZ(), stats:get(CharacterStat.HUNGER), stats:get(CharacterStat.THIRST),
        stats:get(CharacterStat.ENDURANCE))
end

local commands = {}
function commands.ping() return "pong" end
function commands.status() return Lab.status() end

function commands.tp(args)
    local x, y, z = tonumber(args[1]), tonumber(args[2]), tonumber(args[3] or 0)
    Lab.player():teleportTo(x, y, z)
    return string.format("%.1f,%.1f,%d", x, y, z)
end

function commands.lua(_, rest)
    local chunk, err = loadstring(rest)
    if not chunk then return "compile error: " .. tostring(err) end
    local ok, result = pcall(chunk)
    if not ok then return "error: " .. tostring(result) end
    return tostring(result)
end

local function execute(line)
    local seq, verb, rest = line:match("^(%d+)%s+(%S+)%s*(.*)$")
    seq = tonumber(seq)
    if not seq or seq <= Lab.lastSeq then return end
    Lab.lastSeq = seq
    local args = {}
    for word in rest:gmatch("%S+") do args[#args + 1] = word end
    local handler = commands[verb]
    local ok, result
    if handler then ok, result = pcall(handler, args, rest) else ok, result = false, "unknown command" end
    say("ACK", seq, ok and "ok" or "fail", verb, tostring(result))
end

local ready = false
Events.OnPlayerUpdate.Add(function(player)
    if player ~= getSpecificPlayer(0) or ready then return end
    ready = true
    say("READY", Lab.status())
end)

Events.OnTick.Add(function()
    if not ready then return end
    local now = getTimestampMs()
    if now < Lab.nextPoll then return end
    Lab.nextPoll = now + 100
    local lines = readLines("splintlab-client-cmd.txt")
    if lines and lines[1] then execute(lines[1]) end
end)

if getCore().setOptionShowSurvivalGuide then getCore():setOptionShowSurvivalGuide(false) end
