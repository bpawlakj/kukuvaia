---
name: web_search
description: Search the web for current information. Returns titles, URLs, and descriptions. Uses Brave Search API if configured (config.yaml), otherwise falls back to DuckDuckGo Instant Answers.
readOnly: true
concurrencySafe: true
parameters:
  query:
    type: string
    description: "Search query (e.g. 'Spring AI latest release', 'LuaJ Java library')"
    required: true
  max_results:
    type: integer
    description: "Maximum number of results (default 5, max 10)"
    required: false
---
- lua: |
    local max = tonumber(max_results) or tonumber(config and config.max_results_default) or 5
    if max > 10 then max = 10 end

    local encoded = query:gsub(" ", "%%20"):gsub("[^%w%%%-_%.~]", function(c)
      return string.format("%%%02X", string.byte(c))
    end)

    local results = {}

    -- Try Brave Search API if key configured in config.yaml
    local brave_key = config and config.brave_api_key
    if brave_key and brave_key ~= "" then
      local url = "https://api.search.brave.com/res/v1/web/search?q=" .. encoded .. "&count=" .. max
      local response = http.get_with_header(url, "X-Subscription-Token", brave_key)

      if response and not response:find('"error"') then
        for title, link, desc in response:gmatch('"title"%s*:%s*"(.-)".-"url"%s*:%s*"(.-)".-"description"%s*:%s*"(.-)"') do
          if #results < max then
            title = title:gsub('\\"', '"'):gsub('\\/', '/')
            link = link:gsub('\\"', '"'):gsub('\\/', '/')
            desc = desc:gsub('\\"', '"'):gsub('\\/', '/'):gsub('\\n', ' ')
            table.insert(results, {title = title, url = link, snippet = desc})
          end
        end
      end
    end

    -- Fallback: DuckDuckGo Instant Answers (no API key needed)
    if #results == 0 then
      local ddg_url = "https://api.duckduckgo.com/?q=" .. encoded .. "&format=json&no_html=1&skip_disambig=1"
      local ddg = http.get(ddg_url)

      if ddg then
        local abstract = ddg:match('"AbstractText"%s*:%s*"(.-)"')
        local abs_url = ddg:match('"AbstractURL"%s*:%s*"(.-)"')

        if abstract and abstract ~= "" then
          abstract = abstract:gsub('\\"', '"'):gsub('\\/', '/')
          abs_url = abs_url and abs_url:gsub('\\"', '"'):gsub('\\/', '/') or ""
          table.insert(results, {title = "Summary", url = abs_url, snippet = abstract})
        end

        for topic_text, topic_url in ddg:gmatch('"Text"%s*:%s*"(.-)".-"FirstURL"%s*:%s*"(.-)"') do
          if #results < max and topic_text ~= "" then
            topic_text = topic_text:gsub('\\"', '"'):gsub('\\/', '/')
            topic_url = topic_url:gsub('\\"', '"'):gsub('\\/', '/')
            table.insert(results, {title = topic_text:sub(1, 80), url = topic_url, snippet = topic_text})
          end
        end
      end
    end

    if #results == 0 then
      return "No results found for: " .. query
    end

    local output = "Search results for: " .. query .. "\n\n"
    for i, r in ipairs(results) do
      output = output .. i .. ". **" .. r.title .. "**\n"
      output = output .. "   " .. r.url .. "\n"
      if r.snippet ~= "" then
        output = output .. "   " .. r.snippet .. "\n"
      end
      output = output .. "\n"
    end
    return output
