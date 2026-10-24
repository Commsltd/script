"""Reviewed FAST services, persistent playback routes and exact edition guides.

The seed is curated from provider identities and playback checks, not fuzzy
name matching at runtime. Never replace existing identities or alternatives.
"""
import copy
import json
import time
from pathlib import Path
from stream_sources import source


def seed():
    return json.loads(Path(__file__).with_name('fast-channel-seed.json').read_text())


def expand(channels, inventory=None):
    rows = copy.deepcopy(channels)
    by_id = {c['id']: c for c in rows}
    for spec in seed() if inventory is None else inventory:
        channel = by_id.get(spec['id'])
        if channel is None:
            channel = {k: spec[k] for k in ('id', 'family', 'name', 'group', 'variant', 'logo', 'country')}
            channel.update(epgId='', order=len(rows), sources=[])
            rows.append(channel)
            by_id[channel['id']] = channel
        for route in spec['routes']:
            entry = source(route['url'], route.get('headers',{}), route['label'], 1)
            entry['guideChannelId'] = route['guideChannelId']
            entry['region'] = route.get('region') or ('UK' if route['guideSource'] in ('pluto', 'samsung') else 'USA')
            existing = next((s for s in channel['sources'] if s['id'] == entry['id']), None)
            if existing is None:
                channel['sources'].append(entry)
            else:
                existing['guideChannelId'] = route['guideChannelId']
                existing['region'] = entry['region']
    return rows


def add_guides(data, guides, inventory=None, now_ms=None):
    """Provider edition schedules follow the chosen stream, including failover."""
    from export_manifest import read_programmes, identity
    from guide_supplements import parse_xml
    now = int(time.time()*1000) if now_ms is None else now_ms
    specs = seed() if inventory is None else inventory
    stations = {c['id']: c for c in data['channels']}
    # Earlier generic guide passes may assign one guide to every backup.
    # Restore exact route identities before selecting any default schedule.
    for spec in specs:
        channel = stations.get(spec['id'])
        if not channel: continue
        routes = {r['url']: r for r in spec['routes']}
        for stream in channel['sources']:
            route = routes.get(stream['url'])
            if route:
                stream['guideChannelId'] = route['guideChannelId']
                stream['region'] = route.get('region') or ('UK' if route['guideSource'] in ('pluto', 'samsung') else 'USA')
    virtual = {}
    for spec in specs:
        for route in spec['routes']:
            virtual.setdefault(route['guideSource'], {})[route['guideChannelId']] = {
                'id': route['guideChannelId'], 'epgId': route['guideId']}
    schedules = {}
    for provider, mappings in virtual.items():
        if provider not in guides: continue
        programmes = read_programmes(parse_xml(guides[provider]), mappings)
        for programme in programmes:
            # Bound catalogue growth and Firestick database work. Hourly export
            # replenishes the window; expired listings cannot masquerade as live.
            if now - 3*3600000 < programme['stop'] and programme['start'] < now + 48*3600000:
                schedules.setdefault(programme['channelId'], []).append(programme)
    regional = data.setdefault('regionalProgrammes', [])
    seen = {p['key'] for p in regional}
    for programmes in schedules.values():
        for programme in programmes:
            if programme['key'] not in seen:
                regional.append(programme); seen.add(programme['key'])
    for spec in specs:
        channel = stations.get(spec['id'])
        if not channel or not channel['sources']: continue
        primary = channel['sources'][0].get('guideChannelId')
        programmes = schedules.get(primary, [])
        if primary not in {r['guideChannelId'] for r in spec['routes']}: continue
        # Earlier generic Pluto mapping may have matched a backup edition.
        # The actual primary stream's exact schedule takes precedence here.
        data['programmes'] = [p for p in data['programmes'] if p['channelId'] != channel['id']]
        if not any(p['stop'] > now for p in programmes): continue
        for programme in programmes:
            row = dict(programme, channelId=channel['id'])
            row['key'] = identity(f"{row['channelId']}|{row['start']}|{row['stop']}|{row['title']}")
            data['programmes'].append(row)
        data['supplementalGuideSources'].setdefault('fast_exact', []).append(channel['id'])
