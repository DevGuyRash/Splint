-- Splint lab server fixture. Development only: never shipped with Splint.
-- Executes sequence-numbered commands from the server cache's Lua/splintlab-server-cmd.txt
-- and prints [SPLAB-S] lines. It stages animal populations and measures what the game does
-- with them; it never repairs anything itself.

if not isServer() then return end

SplintLabServer = SplintLabServer or { lastSeq = 0, nextPoll = 0, nextBeat = 0, ticks = 0 }
local Lab = SplintLabServer

local function say(...)
    local parts = {}
    for i = 1, select("#", ...) do parts[#parts + 1] = tostring(select(i, ...)) end
    print("[SPLAB-S] " .. table.concat(parts, " "))
end
Lab.say = say

function Lab.player()
    local players = getOnlinePlayers()
    if not players then return nil end
    for i = 0, players:size() - 1 do
        local p = players:get(i)
        if p and not p:isDead() then return p end
    end
end

local function center(args, i)
    local p = Lab.player()
    local x, y = tonumber(args[i]), tonumber(args[i + 1])
    if x and y then return x, y, tonumber(args[i + 2]) or 0 end
    if not p then return nil end
    return math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ())
end

-- Every animal standing on a square within `radius` of (cx, cy, cz), as the squares see
-- them. Square lists are what checkForChickenpocalypse scans, so ghosts show up here even
-- when they are no longer in the cell's update list.
function Lab.animalsOnSquares(cx, cy, cz, radius)
    local out = {}
    local cell = getCell()
    for x = cx - radius, cx + radius do
        for y = cy - radius, cy + radius do
            local sq = cell:getGridSquare(x, y, cz)
            if sq then
                local list = sq:getAnimals()
                for i = 0, list:size() - 1 do out[#out + 1] = list:get(i) end
            end
        end
    end
    return out
end

local function isGhost(a)
    return a:getAnimalID() == -1 or a:getData() == nil or a:getAnimalType() == nil
end

-- census [x y z] [radius]: counts by type, ghosts, duplicate IDs, update-list size.
function Lab.census(cx, cy, cz, radius)
    local animals = Lab.animalsOnSquares(cx, cy, cz, radius)
    local byType, ids, ghosts, dupes = {}, {}, 0, 0
    for _, a in ipairs(animals) do
        if isGhost(a) then
            ghosts = ghosts + 1
        else
            local t = a:getAnimalType()
            byType[t] = (byType[t] or 0) + 1
            local id = a:getAnimalID()
            ids[id] = (ids[id] or 0) + 1
            if ids[id] == 2 then dupes = dupes + 1 end
        end
    end
    local parts = {}
    for t, n in pairs(byType) do parts[#parts + 1] = t .. ":" .. n end
    table.sort(parts)
    return string.format("onSquares=%d ghosts=%d dupIdGroups=%d types=%s",
        #animals, ghosts, dupes, table.concat(parts, ","))
end

local commands = {}
function commands.ping() return "pong" end

function commands.census(a)
    local x, y, z = center(a, 1)
    if not x then return "no player" end
    return Lab.census(x, y, z, tonumber(a[4]) or 40)
end

-- spawn <type> <breed|-> <count> <dx> <dy> <spread> <daysSurvived> [hoursIntoDay]
-- Places animals at an offset from the first player. daysSurvived sets the age the growth
-- check reads; hoursIntoDay lets a stage boundary be crossed at the next in-game hour.
function commands.spawn(a)
    local p = Lab.player()
    if not p then return "no player" end
    local animalType, breedName, count = a[1], a[2], tonumber(a[3] or 1)
    local dx, dy, spread = tonumber(a[4] or 3), tonumber(a[5] or 0), tonumber(a[6] or 0)
    local days, hours = tonumber(a[7] or 0), tonumber(a[8] or 0)
    local def = AnimalDefinitions.getDef(animalType)
    if not def then return "unknown type " .. tostring(animalType) end
    local placed, failed = 0, 0
    for _ = 1, count do
        -- Dry ground only: scenarios that want water place animals there on purpose.
        local x, y
        for _ = 1, 20 do
            x = math.floor(p:getX() + dx + (spread > 0 and ZombRand(-spread, spread + 1) or 0))
            y = math.floor(p:getY() + dy + (spread > 0 and ZombRand(-spread, spread + 1) or 0))
            local sq = getCell():getGridSquare(x, y, p:getZ())
            if sq and not sq:isWaterSquare() then break end
        end
        local breed = breedName ~= "-" and def:getBreedByName(breedName) or def:getRandomBreed()
        local animal = addAnimal(getCell(), x, y, p:getZ(), animalType, breed)
        if animal and animal:getData() then
            animal:addToWorld()
            animal:getData():setAge(days)
            animal:setHoursSurvived(days * 24 + hours)
            placed = placed + 1
        else
            failed = failed + 1
        end
    end
    return string.format("placed=%d failed=%d", placed, failed)
end

-- water [radius]: the nearest water square to the player, as "x y z".
function commands.water(a)
    local p = Lab.player()
    if not p then return "no player" end
    local px, py, pz = math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ())
    local radius = tonumber(a[1]) or 40
    local best, bestD
    for x = px - radius, px + radius do
        for y = py - radius, py + radius do
            local sq = getCell():getGridSquare(x, y, pz)
            if sq and sq:isWaterSquare() then
                local d = (x - px) ^ 2 + (y - py) ^ 2
                if not bestD or d < bestD then best, bestD = sq, d end
            end
        end
    end
    if not best then return "none" end
    return string.format("%d %d %d", best:getX(), best:getY(), best:getZ())
end

function commands.time()
    local gt = getGameTime()
    return string.format("worldAgeHours=%.2f hour=%d day=%d month=%d multiplier=%.2f",
        gt:getWorldAgeHours(), gt:getHour(), gt:getDay(), gt:getMonth(), gt:getMultiplier())
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

-- The heartbeat shows whether Lua ticks keep running while the world update is failing.
Events.OnTick.Add(function()
    Lab.ticks = Lab.ticks + 1
    local now = getTimestampMs()
    if now >= Lab.nextBeat then
        Lab.nextBeat = now + 5000
        say("HB", Lab.ticks, string.format("worldAgeHours=%.3f", getGameTime():getWorldAgeHours()))
    end
    if now < Lab.nextPoll then return end
    Lab.nextPoll = now + 100
    local reader = getFileReader("splintlab-server-cmd.txt", false)
    if not reader then return end
    local line = reader:readLine()
    reader:close()
    if line then execute(line) end
end)

say("LOADED")
