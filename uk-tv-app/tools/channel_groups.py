"""Viewing categories for the native app, independent of upstream playlist labels.

Use service identities, not the country suffix or words in a programme title.
For example, Pluto's .de@GB services belong with their UK viewing genres, and
scripted crime drama belongs with drama rather than factual crime programmes.
Only group/order change: channel, stream and guide identities remain intact.
"""
from __future__ import annotations

import re
import unicodedata

# MAIN UK is also the initial selection in installed version 0.3. Keep its key.
SOURCE_GROUPS = {
    'main': '01 MAIN UK',
    'news': '02 NEWS & POLITICS',
    'films': '03 FILMS',
    'drama': '04 DRAMA & COMEDY',
    'entertainment': '05 ENTERTAINMENT',
    'documentaries': '06 DOCUMENTARIES',
    'crime': '07 CRIME & MYSTERY',
    'lifestyle': '08 FOOD & LIFESTYLE',
    'kids': '09 KIDS',
    'sport': '10 SPORT',
    'music': '11 MUSIC',
    'regional': '12 REGIONAL & LOCAL',
    'world': '13 WORLD & LANGUAGES',
    'faith': '14 FAITH',
    'shopping': '15 SHOPPING',
    'events': '16 LIVE EVENTS',
    'tt': '17 TRINIDAD & TOBAGO',
    'ie': '18 IRELAND',
    'us': '19 USA',
    'ca': '20 CANADA',
    'au': '21 AUSTRALIA',
    'nz': '22 NEW ZEALAND',
    'spain': '23 SPAIN',
    'business': '24 BUSINESS & FINANCE',
    'other': '99 MORE CHANNELS',
}

# Display priority is independent of upstream/seed labels. Keep accepting those
# labels so refreshing a catalogue never loses a channel's category.
VIEWING_ORDER = (
    'main', 'drama', 'films', 'news', 'business', 'documentaries',
    'crime', 'entertainment', 'sport', 'events', 'lifestyle', 'music',
    'regional', 'kids', 'world', 'faith', 'shopping', 'other',
    'au', 'ca', 'ie', 'nz', 'spain', 'tt', 'us',
)
GROUPS = {
    key: f"{index:02d} {SOURCE_GROUPS[key].split(' ', 1)[1]}"
    for index, key in enumerate(VIEWING_ORDER, 1)
}

SERVICES = {
    'main': '''BBCOne.uk BBCTwo.uk ITV1.uk Channel4.uk Channel5.uk
        BBCThree.uk BBCThreeCBBC.uk BBCFour.uk BBCFourCBeebies.uk
        ITV2.uk ITV4.uk E4.uk More4.uk''',
    'news': '''BBCNews.uk SkyNews.uk GBNews.uk BBCParliament.uk BloombergTV.us
        EuronewsEnglish.fr ABCNewsLive.us CBSNews247.us NBCNewsNOW.us
        LiveNOWfromFOX.us Newsmax2.us BBCNewsPashto.uk SkyNewsWeather.uk
        CNBCUK.uk IranInternational.uk TalkTV.uk BBCPersian.uk AajTak.in
        NTDTVEnglish.us DunyaNewsUK.uk''',
    'films': '''Film4.uk TalkingPicturesTV.uk GREATmovies.uk GREATromance.uk
        MovieSphere.us MyTimeMovieNetwork.uk PlutoTVFamilyMovieClub.de
        PlutoTVFantasyHorror.de PlutoTVMovies.de RakutenTVActionMovies.es
        RakutenTVComedyMovies.es RakutenTVDramaMovies.es RakutenTVFamilyMovies.es
        RakutenTVTopMovies.es PlutoTVCultFilms.de PlutoTVWesterns.de Trailers.es
        NollyAfrica.uk''',
    'drama': '''ITV3.uk 5USA.uk UAlibi.uk ThatsTV.uk Andromeda.us
        BeyondBeliefFactorFiction.us CagneyLacey.de CSICrimeSceneInvestigation.de
        HighwaytoHeaven.us MarriedwithChildren.us McLeodsDaughters.us
        MoreTVCrime.de MoreTVScifi.de PlutoTVAnimation.de PlutoTVClassicTV.de
        PlutoTVCrime.de PlutoTVScifiSeries.de PlutoTVSherlock.de PlutoTVSitcoms.de
        SabrinaTheTeenageWitch.de Scorpion.de SouthPark.de TheNanny.us
        WildatHeart.us MrBeanLiveAction.uk''',
    'entertainment': '''UW.uk AuctionHunters.de DuckDynasty.us FailArmy.us
        GeordieShore.de JudgeJudy.de Nosey.us SoReal.us TeenMom.de
        ThePetCollective.us TheYellowCouchwithJeremyLynch.de UndercoverBossGlobal.us
        PlanetFun.uk TheChatShowChannel.uk TheGrahamNortonShow.uk''',
    'documentaries': '''Quest.uk Blaze.uk AdventureEarth.de AutenticHistory.de
        Getfactual.us Mythbusters.us NatureTime.ca PlutoTVAlienInvasion.de
        PlutoTVAnimals.de PlutoTVBritainatWar.de PlutoTVConspiracy.de
        PlutoTVPetrolHeads.de PlutoTVSpace.de PlutoTVToughJobs.de
        TerraMaterWILD.de WaterBear.ch Wonder.uk TopGear.uk''',
    'crime': '''TrueCrime.uk 5Cops.us DeadlyWomen.us DogtheBountyHunter.us
        FBIFiles.us GhostDimension.us GhostHunters.us HomicideHunter.us
        InsideCrime.us MysteryTV.us OnTheCase.us ParanormalState.us
        PlutoTVAmericanTrueCrime.de PlutoTVInvestigation.de SensingMurder.us
        TheNewDetectives.de UnsolvedMysteries.us MostHaunted.uk''',
    'lifestyle': '''FoodNetwork.uk AnthonyBourdainPartsUnknown.us AutenticTravel.de
        DogWhisperer.us HellsKitchen.us HomesUnderHammer.us PlutoTVFood.de
        Tastemade.us Foodxp.uk TogetherTV.uk''',
    'kids': '''BabyTV.uk CBBC.uk CBeebies.uk MoonbugKids.uk Nickelodeon.uk
        Pop.uk PopUp.uk TinyPop.uk Avatar.de PlutoTVRetroToons.de
        TotallyTurtles.de MrBeanAnimated.uk Teletubbies.uk YAAAS.uk''',
    'sport': '''FIFAPlus.uk FIFAPlusWomen.uk SkySportsF1.uk SkySportsNFL.uk
        talkSPORT.uk MUTV.uk RugbyPassTV.uk RedBullTV.at Strongman.us
        WorldPokerTour.us DAZNCombat.uk HorseCountryTV.uk''',
    'music': '''KNMusicTV.az Now80s.uk Totalmusic.uk Totalmusic2000s.uk
        Totalmusic80s.uk TotalmusicConcerts.uk TotalmusicDance.uk VantageMusic.ee
        ClubbingTV.us QwestTVJazzBeyond.fr XITEHits.nl AfrobeatTVEntertainment.uk
        BritAsiaTV.uk Now70s.uk NOWRock.uk Thats70s.uk Thats80s.uk TraceUK.uk
        V2BEATTV.uk''',
    'regional': '''BBCAlba.uk BBCScotland.uk S4C.uk STV.uk LatestTV.uk
        SheffieldLiveTV.uk''',
    'world': '''ATNBanglaUK.uk Canaf54TV.uk ChannelS.uk DeenTV.uk DerTV.uk
        EnglishClubTV.uk ENTChannel.uk ENTFamily.uk HalaLondon.uk IBCTamil.uk
        IonTV.uk KalsanTV.uk KanalJadid.uk MagnavisionTV.uk MBC.uk SparkTV.uk
        StarBharat.uk StarPlus.uk StarVijay.in TVOne.uk UtsavBharat.uk UtsavPlus.uk
        ZeeOne.uk AathavanTV.uk AlHiwarTV.uk AlbUKTV.uk AnandTV.uk GarshomTV.uk
        HellenicTV.uk''',
    'faith': '''AhlulbaytTV.uk EmanChannel.uk FaithUK.uk FaithWorldTV.uk GODTV.uk
        IqraBangla.uk IqraTV.uk MTA1World.uk MTA2Europe.uk MTA3AlArabia.uk
        MTA4Africa.uk MTA5Africa.uk MTA6Asia.uk MTA7Asia.uk MTA8America.uk
        NoorTV.uk TakbeerTV.uk TBNUK.uk KTO.fr SanskarUK.in AkaalChannel.uk
        IBCBakthi.uk MiracleTV.uk PanjabTV.uk SafeerTV.uk SangatTV.uk
        IslamChannel.uk KanshiTV.uk LoveWorldUK.ng''',
    'shopping': '''HobbyMaker.uk QVC.uk QVCBeauty.uk QVCExtra.uk QVCStyle.uk
        TJC.uk TVWarehouse.uk GemsTV.uk''',
}

SERVICE_GROUP = {}
for _group, _services in SERVICES.items():
    for _service in _services.split():
        if _service in SERVICE_GROUP:
            raise ValueError(f'Duplicate channel category: {_service}')
        SERVICE_GROUP[_service] = _group

# Unknown services retain a useful upstream genre where available. Neither
# "Not 24/7" nor "Geo-blocked" is a genre or a measured reliability result.
LEGACY_GROUPS = {
    '01 MAIN UK': 'main', '02 NEWS': 'news',
    '03 FILM & ENTERTAINMENT': 'films', '04 KIDS': 'kids',
    '05 MUSIC': 'music', '06 SPORT': 'sport',
    '07 LOCAL & REGIONAL': 'regional', '08 INTERNATIONAL': 'world',
    '09 RELIGIOUS': 'faith', '10 SHOPPING': 'shopping',
    '80 RED BUTTON / SPECIAL EVENTS': 'events',
}

# Familiar broadcasters lead each group; the remainder are alphabetical.
FEATURED = {
    'main': '''BBCOne.uk BBCTwo.uk ITV1.uk Channel4.uk Channel5.uk BBCThree.uk
        BBCThreeCBBC.uk BBCFour.uk BBCFourCBeebies.uk ITV2.uk ITV4.uk E4.uk More4.uk'''.split(),
    'news': 'BBCNews.uk SkyNews.uk GBNews.uk BBCParliament.uk'.split(),
    'films': 'Film4.uk TalkingPicturesTV.uk GREATmovies.uk GREATromance.uk'.split(),
    'drama': 'ITV3.uk 5USA.uk UAlibi.uk ThatsTV.uk'.split(),
    'kids': 'CBBC.uk CBeebies.uk'.split(),
}


# Familiar national services lead each new country shelf.
from international_channels import COUNTRIES, channel_country, label_news_countries
FEATURED.update({code.lower(): ids for code, (_, ids) in COUNTRIES.items()})


BUSINESS_SERVICES = {
    'CNBCUK.uk', 'BloombergTV.us', 'BloombergOriginals.us', 'CheddarNews.us',
    'YahooFinance.us', 'FASTbusinessinsider.us', 'BNNBloomberg.ca', 'AusbizTV.au', 'tickerNews.au',
}


def category(channel):
    base, _, variant = channel['id'].partition('@')
    if channel.get('group') in {GROUPS['spain'], SOURCE_GROUPS['spain']}: return 'spain'
    if base in BUSINESS_SERVICES: return 'business'
    # Preserve the existing London/default main services; do not relabel other
    # regional schedules as London or merge regional favourites/stream health.
    if base in {'BBCOne.uk', 'BBCTwo.uk', 'ITV1.uk'} and variant not in {
        '', 'SD', 'HD', 'UK', 'UKHD', 'London', 'LondonHD', 'LondonPlus1',
    }:
        return 'regional'
    if re.fullmatch(r'BBCRedButton\d+\.uk|BBCUHD\d+\.uk', base):
        return 'events'
    if base in SERVICE_GROUP:
        return SERVICE_GROUP[base]
    previous = channel.get('group', '')
    return next((key for key, label in GROUPS.items() if label == previous),
                next((key for key, label in SOURCE_GROUPS.items() if label == previous),
                     LEGACY_GROUPS.get(previous, 'other')))


def natural(value):
    return tuple((0, int(p)) if p.isdigit() else (1, p.casefold())
                 for p in re.split(r'(\d+)', value) if p)


def arrange_channels(channels):
    """Return a stable list with only its group and sort order updated."""
    rows = list(channels)
    ranks = {group: i for i, group in enumerate(GROUPS)}

    def key(channel):
        group = category(channel)
        base, _, variant = channel['id'].partition('@')
        if group == 'spain':
            return (ranks[group], not channel.get('streamVerified',False),
                    not channel.get('englishSubtitles',False),
                    natural(''.join(c for c in unicodedata.normalize('NFKD',channel['name']) if not unicodedata.combining(c))),
                    channel['id'])
        featured = FEATURED.get(group, [])
        priority = featured.index(base) if base in featured else len(featured)
        country = channel_country(channel)
        variant_key = (natural(variant.removesuffix('HD')),
                       0 if variant.endswith('HD') else 1, natural(variant))
        # Only explicitly featured UK broadcasters retain their familiar order.
        # Every other service sorts by its visible name, never its directory ID.
        if country == 'UK' and base in featured:
            return (ranks[group], 0, priority, natural(base), variant_key, channel['id'])
        return (ranks[group], 1, 0 if country == 'UK' else 1,
                natural(country), natural(channel['name']), natural(base),
                variant_key, channel['id'])

    rows.sort(key=key)
    for order, channel in enumerate(rows):
        channel['group'] = GROUPS[category(channel)]
        channel['order'] = order
        if channel['group'] == GROUPS['business']:
            country = channel_country(channel)
            if country and not channel['name'].endswith(' ('+country+')'):
                channel['name'] += ' ('+country+')'
    return label_news_countries(rows)
