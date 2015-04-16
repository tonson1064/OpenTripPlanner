/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.api.common;

import java.util.*;

import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.opentripplanner.api.parameter.QualifiedModeSetSequence;
import org.opentripplanner.routing.core.OptimizeType;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.request.BannedStopSet;
import org.opentripplanner.standalone.OTPServer;
import org.opentripplanner.standalone.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class defines all the JAX-RS query parameters for a path search as fields, allowing them to 
 * be inherited by other REST resource classes (the trip planner and the Analyst WMS or tile 
 * resource). They will be properly included in API docs generated by Enunciate. This implies that
 * the concrete REST resource subclasses will be request-scoped rather than singleton-scoped.
 * 
 * @author abyrd
 */
public abstract class RoutingResource { 

    private static final Logger LOG = LoggerFactory.getLogger(RoutingResource.class);

    /**
     * The routerId selects between several graphs on the same server. The routerId is pulled from
     * the path, not the query parameters. However, the class RoutingResource is not annotated with
     * a path because we don't want it to be instantiated as an endpoint. Instead, the {routerId}
     * path parameter should be included in the path annotations of all its subclasses.
     */
    @PathParam("routerId") 
    public String routerId;

    /* TODO do not specify @DefaultValues here, so all defaults are handled in one place */

    /** The start location -- either latitude, longitude pair in degrees or a Vertex
     *  label. For example, <code>40.714476,-74.005966</code> or
     *  <code>mtanyctsubway_A27_S</code>.  */
    @QueryParam("fromPlace") protected List<String> fromPlace;

    /** The end location (see fromPlace for format). */
    @QueryParam("toPlace") protected List<String> toPlace;

    /** An ordered list of intermediate locations to be visited (see the fromPlace for format). */
    @QueryParam("intermediatePlaces") protected List<String> intermediatePlaces;

    /** The date that the trip should depart (or arrive, for requests where arriveBy is true). */
    @QueryParam("date") protected List<String> date;
    
    /** The time that the trip should depart (or arrive, for requests where arriveBy is true). */
    @QueryParam("time") protected List<String> time;
    
    /** Whether the trip should depart or arrive at the specified date and time. */
    @DefaultValue("false") @QueryParam("arriveBy") protected List<Boolean> arriveBy;
    
    /** Whether the trip must be wheelchair accessible. */
    @DefaultValue("false") @QueryParam("wheelchair") protected List<Boolean> wheelchair;

    /** The maximum distance (in meters) the user is willing to walk. Defaults to unlimited. */
    @QueryParam("maxWalkDistance") protected List<Double> maxWalkDistance;

    /**
     * The maximum time (in seconds) of pre-transit travel when using drive-to-transit (park and
     * ride or kiss and ride). Defaults to unlimited.
     */
    @DefaultValue("-1") @QueryParam("maxPreTransitTime") protected List<Integer> maxPreTransitTime;

    /** A multiplier for how bad walking is, compared to being in transit for equal lengths of time.
     *  Defaults to 2. Empirically, values between 10 and 20 seem to correspond well to the concept
     *  of not wanting to walk too much without asking for totally ridiculous itineraries, but this
     *  observation should in no way be taken as scientific or definitive. Your mileage may vary.*/
    @QueryParam("walkReluctance") protected List<Double> walkReluctance;

    /**
     * How much worse is waiting for a transit vehicle than being on a transit vehicle, as a
     * multiplier. The default value treats wait and on-vehicle time as the same.
     *
     * It may be tempting to set this higher than walkReluctance (as studies often find this kind of
     * preferences among riders) but the planner will take this literally and walk down a transit
     * line to avoid waiting at a stop. This used to be set less than 1 (0.95) which would make
     * waiting offboard preferable to waiting onboard in an interlined trip. That is also
     * undesirable.
     *
     * If we only tried the shortest possible transfer at each stop to neighboring stop patterns,
     * this problem could disappear.
     */
    @QueryParam("waitReluctance") protected List<Double> waitReluctance;

    /** How much less bad is waiting at the beginning of the trip (replaces waitReluctance) */
    @QueryParam("waitAtBeginningFactor") protected List<Double> waitAtBeginningFactor;

    /** The user's walking speed in meters/second. Defaults to approximately 3 MPH. */
    @QueryParam("walkSpeed") protected List<Double> walkSpeed;

    /** The user's biking speed in meters/second. Defaults to approximately 11 MPH, or 9.5 for bikeshare. */
    @QueryParam("bikeSpeed") protected List<Double> bikeSpeed;

    /** The time it takes the user to fetch their bike and park it again in seconds.
     *  Defaults to 0. */
    @QueryParam("bikeSwitchTime") protected List<Integer> bikeSwitchTime;

    /** The cost of the user fetching their bike and parking it again.
     *  Defaults to 0. */
    @QueryParam("bikeSwitchCost") protected List<Integer> bikeSwitchCost;

    /** For bike triangle routing, how much safety matters (range 0-1). */
    @QueryParam("triangleSafetyFactor") protected List<Double> triangleSafetyFactor;
    
    /** For bike triangle routing, how much slope matters (range 0-1). */
    @QueryParam("triangleSlopeFactor") protected List<Double> triangleSlopeFactor;
    
    /** For bike triangle routing, how much time matters (range 0-1). */            
    @QueryParam("triangleTimeFactor") protected List<Double> triangleTimeFactor;

    /** The set of characteristics that the user wants to optimize for. @See OptimizeType */
    @DefaultValue("QUICK") @QueryParam("optimize") protected List<OptimizeType> optimize;
    
    /** The set of modes that a user is willing to use, with qualifiers stating whether vehicles should be parked, rented, etc. */
    @DefaultValue("TRANSIT,WALK") @QueryParam("mode") protected List<QualifiedModeSetSequence> modes;

    /** The minimum time, in seconds, between successive trips on different vehicles.
     *  This is designed to allow for imperfect schedule adherence.  This is a minimum;
     *  transfers over longer distances might use a longer time. */
    @DefaultValue("-1") @QueryParam("minTransferTime") protected List<Integer> minTransferTime;

    /** The maximum number of possible itineraries to return. */
    @DefaultValue("-1") @QueryParam("numItineraries") protected List<Integer> numItineraries;

    /**
     * The list of preferred routes. The format is agency_[routename][_routeid], so TriMet_100 (100 is route short name) or Trimet__42 (two
     * underscores, 42 is the route internal ID).
     */
    @DefaultValue("") @QueryParam("preferredRoutes") protected List<String> preferredRoutes;

    /** Penalty added for using every route that is not preferred if user set any route as preferred, i.e. number of seconds that we are willing
     * to wait for preferred route. */
    @DefaultValue("-1") @QueryParam("otherThanPreferredRoutesPenalty") protected List<Integer> otherThanPreferredRoutesPenalty;
    
    /** The comma-separated list of preferred agencies. */
    @DefaultValue("") @QueryParam("preferredAgencies") protected List<String> preferredAgencies;
    
    /**
     * The list of unpreferred routes. The format is agency_[routename][_routeid], so TriMet_100 (100 is route short name) or Trimet__42 (two
     * underscores, 42 is the route internal ID).
     */
    @DefaultValue("") @QueryParam("unpreferredRoutes") protected List<String> unpreferredRoutes;
    
    /** The comma-separated list of unpreferred agencies. */
    @DefaultValue("") @QueryParam("unpreferredAgencies") protected List<String> unpreferredAgencies;

    /** Whether intermediate stops -- those that the itinerary passes in a vehicle, but 
     *  does not board or alight at -- should be returned in the response.  For example,
     *  on a Q train trip from Prospect Park to DeKalb Avenue, whether 7th Avenue and
     *  Atlantic Avenue should be included. */
    @DefaultValue("false") @QueryParam("showIntermediateStops") protected List<Boolean> showIntermediateStops;

    /**
     * Prevents unnecessary transfers by adding a cost for boarding a vehicle. This is the cost that
     * is used when boarding while walking.
     */
    @DefaultValue("-1") @QueryParam("walkBoardCost") protected List<Integer> walkBoardCost;
    
    /**
     * Prevents unnecessary transfers by adding a cost for boarding a vehicle. This is the cost that
     * is used when boarding while cycling. This is usually higher that walkBoardCost.
     */
    @DefaultValue("-1") @QueryParam("bikeBoardCost") protected List<Integer> bikeBoardCost;
    
    /**
     * The comma-separated list of banned routes. The format is agency_[routename][_routeid], so TriMet_100 (100 is route short name) or Trimet__42
     * (two underscores, 42 is the route internal ID).
     */
    @DefaultValue("") @QueryParam("bannedRoutes") protected List<String> bannedRoutes;
    
    /** The comma-separated list of banned agencies. */
    @DefaultValue("") @QueryParam("bannedAgencies") protected List<String> bannedAgencies;
    
    /** The comma-separated list of banned trips.  The format is agency_trip[:stop*], so:
     * TriMet_24601 or TriMet_24601:0:1:2:17:18:19
     */
    @DefaultValue("") @QueryParam("bannedTrips") protected List<String> bannedTrips;

    /** A comma-separated list of banned stops. A stop is banned by ignoring its 
     * pre-board and pre-alight edges. This means the stop will be reachable via the
     * street network. Also, it is still possible to travel through the stop. Just
     * boarding and alighting is prohibited.
     * The format is agencyId_stopId, so: TriMet_2107
     */
    @DefaultValue("") @QueryParam("bannedStops") protected List<String> bannedStops;
    
    /** A comma-separated list of banned stops. A stop is banned by ignoring its 
     * pre-board and pre-alight edges. This means the stop will be reachable via the
     * street network. It is not possible to travel through the stop.
     * For example, this parameter can be used when a train station is destroyed, such
     * that no trains can drive through the station anymore.
     * The format is agencyId_stopId, so: TriMet_2107
     */
    @DefaultValue("") @QueryParam("bannedStopsHard") protected List<String> bannedStopsHard;
    
    /**
     * An additional penalty added to boardings after the first.  The value is in OTP's
     * internal weight units, which are roughly equivalent to seconds.  Set this to a high
     * value to discourage transfers.  Of course, transfers that save significant
     * time or walking will still be taken.
     */
    @DefaultValue("-1") @QueryParam("transferPenalty") protected List<Integer> transferPenalty;
    
    /**
     * An additional penalty added to boardings after the first when the transfer is not
     * preferred. Preferred transfers also include timed transfers. The value is in OTP's
     * internal weight units, which are roughly equivalent to seconds. Set this to a high
     * value to discourage transfers that are not preferred. Of course, transfers that save
     * significant time or walking will still be taken.
     * When no preferred or timed transfer is defined, this value is ignored.
     */
    @DefaultValue("-1") @QueryParam("nonpreferredTransferPenalty") protected List<Integer> nonpreferredTransferPenalty;
    
    /** The maximum number of transfers (that is, one plus the maximum number of boardings)
     *  that a trip will be allowed.  Larger values will slow performance, but could give
     *  better routes.  This is limited on the server side by the MAX_TRANSFERS value in
     *  org.opentripplanner.api.ws.Planner. */
    @DefaultValue("-1") @QueryParam("maxTransfers") protected List<Integer> maxTransfers;

    /** If true, goal direction is turned off and a full path tree is built (specify only once) */
    @DefaultValue("false") @QueryParam("batch") protected List<Boolean> batch;

    /** A transit stop required to be the first stop in the search (AgencyId_StopId) */
    @DefaultValue("") @QueryParam("startTransitStopId") protected List<String> startTransitStopId;

    /** A transit trip acting as a starting "state" for depart-onboard routing (AgencyId_TripId) */
    @DefaultValue("") @QueryParam("startTransitTripId") protected List<String> startTransitTripId;

    /**
     * When subtracting initial wait time, do not subtract more than this value, to prevent overly
     * optimistic trips. Reasoning is that it is reasonable to delay a trip start 15 minutes to 
     * make a better trip, but that it is not reasonable to delay a trip start 15 hours; if that
     * is to be done, the time needs to be included in the trip time. This number depends on the
     * transit system; for transit systems where trips are planned around the vehicles, this number
     * can be much higher. For instance, it's perfectly reasonable to delay one's trip 12 hours if
     * one is taking a cross-country Amtrak train from Emeryville to Chicago. Has no effect in
     * stock OTP, only in Analyst.
     *
     * A value of 0 means that initial wait time will not be subtracted out (will be clamped to 0).
     * A value of -1 (the default) means that clamping is disabled, so any amount of initial wait 
     * time will be subtracted out.
     */
    @DefaultValue("-1") @QueryParam("clampInitialWait")
    protected List<Long> clampInitialWait;

    /**
     * If true, this trip will be reverse-optimized on the fly. Otherwise, reverse-optimization
     * will occur once a trip has been chosen (in Analyst, it will not be done at all).
     */
    @QueryParam("reverseOptimizeOnTheFly")
    protected List<Boolean> reverseOptimizeOnTheFly;
        
    @DefaultValue("-1") @QueryParam("boardSlack")
    private List<Integer> boardSlack;
    
    @DefaultValue("-1") @QueryParam("alightSlack")
    private List<Integer> alightSlack;

    @DefaultValue("en_US") @QueryParam("locale")
    private List<String> locale;
    
    /**
     * If true, realtime updates are ignored during this search.
     */
    @QueryParam("ignoreRealtimeUpdates")
    protected List<Boolean> ignoreRealtimeUpdates;

    /**
     * If true, the remaining weight heuristic is disabled. Currently only implemented for the long
     * distance path service.
     */
    @QueryParam("disableRemainingWeightHeuristic")
    protected List<Boolean> disableRemainingWeightHeuristic;
    
    /*
     * AGGIUNTA: parametro per decidere se voglio strade pedonali o meno
     */
    @DefaultValue ("false")
    @QueryParam("footway") protected List<Boolean> permitFootway;
    
    
    /*
     * AGGIUNTA: parametro per decidere se posso attraversare i tornelli o meno
     */
    @DefaultValue ("true")
    @QueryParam("allowBollards") protected List<Boolean> permitBollards;
    
    
    //AGGIUNTA: parametri preferenza
    @DefaultValue("-1") @QueryParam("permitCrossing") protected List<Integer> permitCrossing;
    @DefaultValue("-1") @QueryParam("permitBollard") protected List<Integer> permitBollard;
    @DefaultValue("-1") @QueryParam("permitTurnstile") protected List<Integer> permitTurnstile;
    @DefaultValue("-1") @QueryParam("permitCycleBarrier") protected List<Integer> permitCycleBarrier;
    @DefaultValue("-1") @QueryParam("permitTrafficLightSound") protected List<Integer> permitTrafficLightSound;
    @DefaultValue("-1") @QueryParam("permitTrafficLightVibration") protected List<Integer> permitTrafficLightVibration;
    @DefaultValue("-1") @QueryParam("permitTrafficLightVibrationFloor") protected List<Integer> permitTrafficLightVibrationFloor;
    
    /* 
     * somewhat ugly bug fix: the graphService is only needed here for fetching per-graph time zones. 
     * this should ideally be done when setting the routing context, but at present departure/
     * arrival time is stored in the request as an epoch time with the TZ already resolved, and other
     * code depends on this behavior. (AMB)
     * Alternatively, we could eliminate the separate RoutingRequest objects and just resolve
     * vertices and timezones here right away, but just ignore them in semantic equality checks.
     */
    @Context
    protected OTPServer otpServer;

    /** 
     * Build the 0th Request object from the query parameter lists. 
     * @throws ParameterException when there is a problem interpreting a query parameter
     */
    protected RoutingRequest buildRequest() throws ParameterException {
        return buildRequest(0);
    }
    
    /** 
     * Range/sanity check the query parameter fields and build a Request object from them.
     * @param  n allows building several request objects from the same query parameters, 
     *         re-specifying only those parameters that change from one request to the next. 
     * @throws ParameterException when there is a problem interpreting a query parameter
     */
    protected RoutingRequest buildRequest(int n) throws ParameterException {
        RoutingRequest request = otpServer.routingRequest.clone();
        request.setFromString(get(fromPlace, n, request.getFromPlace().getRepresentation()));
        request.setToString(get(toPlace, n, request.getToPlace().getRepresentation()));
        request.routerId = routerId;
        {
            //FIXME: get defaults for these from request
            String d = get(date, n, null);
            String t = get(time, n, null);
            TimeZone tz;
            Router router = otpServer.getRouter(request.routerId);
            tz = router.graph.getTimeZone();
            if (d == null && t != null) { // Time was provided but not date
                LOG.debug("parsing ISO datetime {}", t);
                try {
                    // If the time query param doesn't specify a timezone, use the graph's default. See issue #1373.
                    DatatypeFactory df = javax.xml.datatype.DatatypeFactory.newInstance();
                    XMLGregorianCalendar xmlGregCal = df.newXMLGregorianCalendar(t);
                    GregorianCalendar gregCal = xmlGregCal.toGregorianCalendar();
                    if (xmlGregCal.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
                        gregCal.setTimeZone(tz);
                    }
                    Date d2 = gregCal.getTime();
                    request.setDateTime(d2);
                } catch (DatatypeConfigurationException e) {
                    request.setDateTime(d, t, tz);
                }
            } else {
                request.setDateTime(d, t, tz);
            }
        }
        /*AGGIUNTA: settaggio del campo permitFootway e delle preferenze*/
        request.setPermitCrossing(get(permitCrossing, n, request.permitCrossing));
        request.setPermitBollard(get(permitBollard, n, request.permitBollard));
        request.setPermitCycleBarrier(get(permitCycleBarrier, n, request.permitCycleBarrier));
        request.setPermitTurnstile(get(permitTurnstile, n , request.permitTurnstile));
        request.setPermitTrafficLightSound(get(permitTrafficLightSound, n, request.permitTrafficLightSound));
        request.setPermitTrafficLightVibration(get(permitTrafficLightVibration, n, request.permitTrafficLightVibration));
        request.setPermitTrafficLightVibrationFloor(get(permitTrafficLightVibrationFloor, n, request.permitTrafficLightVibrationFloor));
        
        System.out.print("Contenuto request:\n"+ 
        				  "Crossing:" + request.permitCrossing + "\n" +
        				  "Bollard:" + request.permitBollard + "\n" +
        				  "Cyclebarrier:" + request.permitCycleBarrier + "\n" +
        				  "Turnstile:" + request.permitTurnstile + "\n" +
        				  "TrafficLightSound:" + request.permitTrafficLightSound + "\n" +
        				  "TrafficLightVibration:" + request.permitTrafficLightVibration + "\n" +
        				  "TrafficLightVibrationFloor:" + request.permitTrafficLightVibrationFloor + "\n");
        
        System.out.print("Contenuto della form:\n"+ 
				  "Crossing:" + permitCrossing + "\n" +
				  "Bollard:" + permitBollard + "\n" +
				  "Cyclebarrier:" + permitCycleBarrier + "\n" +
				  "Turnstile:" + permitTurnstile + "\n" +
				  "TrafficLightSound:" + permitTrafficLightSound + "\n" +
				  "TrafficLightVibration:" + permitTrafficLightVibration + "\n" +
				  "TrafficLightVibrationFloor:" + permitTrafficLightVibrationFloor + "\n");
        
        
        request.setPermitFootway(get(permitFootway, n, request.permitFootway));
        request.setWheelchairAccessible(get(wheelchair, n, request.wheelchairAccessible));
        request.setNumItineraries(get(numItineraries, n, request.getNumItineraries()));
        request.setMaxWalkDistance(get(maxWalkDistance, n, request.getMaxWalkDistance()));
        request.setMaxPreTransitTime(get(maxPreTransitTime, n, request.maxPreTransitTime));
        request.setWalkReluctance(get(walkReluctance, n, request.walkReluctance));
        request.setWaitReluctance(get(waitReluctance, n, request.waitReluctance));
        request.setWaitAtBeginningFactor(get(waitAtBeginningFactor, n, request.waitAtBeginningFactor));
        request.walkSpeed = get(walkSpeed, n, request.walkSpeed);
        double bikeSpeedParam = get(bikeSpeed, n, request.bikeSpeed);
        request.bikeSpeed = bikeSpeedParam;
        int bikeSwitchTimeParam = get(bikeSwitchTime, n, request.bikeSwitchTime);
        request.bikeSwitchTime = bikeSwitchTimeParam;
        int bikeSwitchCostParam = get(bikeSwitchCost, n, request.bikeSwitchCost);
        request.bikeSwitchCost = bikeSwitchCostParam;
        OptimizeType opt = get(optimize, n, request.optimize);
        {
            Double tsafe =  get(triangleSafetyFactor, n, null);
            Double tslope = get(triangleSlopeFactor,  n, null);
            Double ttime =  get(triangleTimeFactor,   n, null);
            if (tsafe != null || tslope != null || ttime != null ) {
                if (tsafe == null || tslope == null || ttime == null) {
                    throw new ParameterException(Message.UNDERSPECIFIED_TRIANGLE);
                }
                if (opt == null) {
                    opt = OptimizeType.TRIANGLE;
                } else if (opt != OptimizeType.TRIANGLE) {
                    throw new ParameterException(Message.TRIANGLE_OPTIMIZE_TYPE_NOT_SET);
                }
                if (Math.abs(tsafe + tslope + ttime - 1) > Math.ulp(1) * 3) {
                    throw new ParameterException(Message.TRIANGLE_NOT_AFFINE);
                }
                request.setTriangleSafetyFactor(tsafe);
                request.setTriangleSlopeFactor(tslope);
                request.setTriangleTimeFactor(ttime);
            } else if (opt == OptimizeType.TRIANGLE) {
                throw new ParameterException(Message.TRIANGLE_VALUES_NOT_SET);
            }
        }
        request.setArriveBy(get(arriveBy, n, false));
        request.showIntermediateStops = get(showIntermediateStops, n, request.showIntermediateStops);
        /* intermediate places and their ordering are shared because they are themselves a list */
        if (intermediatePlaces != null && intermediatePlaces.size() > 0 
            && ! intermediatePlaces.get(0).equals("")) {
            request.setIntermediatePlacesFromStrings(intermediatePlaces);
        }
        request.setPreferredRoutes(get(preferredRoutes, n, request.getPreferredRouteStr()));
        request.setOtherThanPreferredRoutesPenalty(get(otherThanPreferredRoutesPenalty, n, request.otherThanPreferredRoutesPenalty));
        request.setPreferredAgencies(get(preferredAgencies, n, request.getPreferredAgenciesStr()));
        request.setUnpreferredRoutes(get(unpreferredRoutes, n, request.getUnpreferredRouteStr()));
        request.setUnpreferredAgencies(get(unpreferredAgencies, n, request.getUnpreferredAgenciesStr()));
        request.setWalkBoardCost(get(walkBoardCost, n, request.walkBoardCost));
        request.setBikeBoardCost(get(bikeBoardCost, n, request.bikeBoardCost));
        request.setBannedRoutes(get(bannedRoutes, n, request.getBannedRouteStr()));
        request.setBannedAgencies(get(bannedAgencies, n, request.getBannedAgenciesStr()));
        HashMap<AgencyAndId, BannedStopSet> bannedTripMap = makeBannedTripMap(get(bannedTrips, n, null));
        if (bannedTripMap != null) {
            request.bannedTrips = bannedTripMap;
        }
        request.setBannedStops(get(bannedStops, n, request.getBannedStopsStr()));
        request.setBannedStopsHard(get(bannedStopsHard, n, request.getBannedStopsHardStr()));
        
        // "Least transfers" optimization is accomplished via an increased transfer penalty.
        // See comment on RoutingRequest.transferPentalty.
        if (opt == OptimizeType.TRANSFERS) {
            opt = OptimizeType.QUICK;
            request.transferPenalty = get(transferPenalty, n, 0) + 1800;
        } else {
            request.transferPenalty = (get(transferPenalty, n, request.transferPenalty));
        }
        request.batch = (get(batch, n, new Boolean(request.batch)));
        request.setOptimize(opt);
        /* Temporary code to get bike/car parking and renting working. */
        modes.get(0).applyToRequest(request);

        if (request.allowBikeRental && bikeSpeedParam == -1) {
            //slower bike speed for bike sharing, based on empirical evidence from DC.
            request.bikeSpeed = 4.3;
        }

        request.boardSlack = (get(boardSlack, n, request.boardSlack));
        request.alightSlack = (get(alightSlack, n, request.alightSlack));
        request.transferSlack = (get(minTransferTime, n, request.transferSlack));
        request.nonpreferredTransferPenalty = get(nonpreferredTransferPenalty, n, request.nonpreferredTransferPenalty);

        if (request.boardSlack + request.alightSlack > request.transferSlack) {
            throw new RuntimeException("Invalid parameters: transfer slack must "
                    + "be greater than or equal to board slack plus alight slack");
        }

        request.setMaxTransfers(get(maxTransfers, n, request.maxTransfers));
        final long NOW_THRESHOLD_MILLIS = 15 * 60 * 60 * 1000;
        boolean tripPlannedForNow = Math.abs(request.getDateTime().getTime() - new Date().getTime()) 
                < NOW_THRESHOLD_MILLIS;
        request.useBikeRentalAvailabilityInformation = (tripPlannedForNow);

        String startTransitStopId = get(this.startTransitStopId, n,
                AgencyAndId.convertToString(request.startingTransitStopId));
        if (startTransitStopId != null && !"".equals(startTransitStopId)) {
            request.startingTransitStopId = (AgencyAndId.convertFromString(startTransitStopId));
        }
        String startTransitTripId = get(this.startTransitTripId, n,
                AgencyAndId.convertToString(request.startingTransitTripId));
        if (startTransitTripId != null && !"".equals(startTransitTripId)) {
            request.startingTransitTripId = (AgencyAndId.convertFromString(startTransitTripId));
        }
        
        request.clampInitialWait = (get(clampInitialWait, n, request.clampInitialWait));

        request.reverseOptimizeOnTheFly = (get(reverseOptimizeOnTheFly, n, 
                                               request.reverseOptimizeOnTheFly));

        request.ignoreRealtimeUpdates = (get(ignoreRealtimeUpdates, n, 
                request.ignoreRealtimeUpdates));

        request.disableRemainingWeightHeuristic = (get(disableRemainingWeightHeuristic, n,
                request.disableRemainingWeightHeuristic));
        
        String localeSpec = get(locale, n, "en");
        String[] localeSpecParts = localeSpec.split("_");
        Locale locale;
        switch (localeSpecParts.length) {
            case 1:
                locale = new Locale(localeSpecParts[0]);
                break;
            case 2:
                locale = new Locale(localeSpecParts[0]);
                break;
            case 3:
                locale = new Locale(localeSpecParts[0]);
                break;
            default:
                LOG.debug("Bogus locale " + localeSpec + ", defaulting to en");
                locale = new Locale("en");
        }

        request.locale = locale;
        return request;
    }

    /**
     * Take a string in the format agency:id or agency:id:1:2:3:4.
     * Convert to a Map from trip --> set of int.
     */
    private HashMap<AgencyAndId, BannedStopSet> makeBannedTripMap(String banned) {
        if (banned == null) {
            return null;
        }
        
        HashMap<AgencyAndId, BannedStopSet> bannedTripMap = new HashMap<AgencyAndId, BannedStopSet>();
        String[] tripStrings = banned.split(",");
        for (String tripString : tripStrings) {
            // TODO this apparently allows banning stops within a trip with integers. Why?
            String[] parts = tripString.split(":");
            if (parts.length < 2) continue; // throw exception?
            String agencyIdString = parts[0];
            String tripIdString = parts[1];
            AgencyAndId tripId = new AgencyAndId(agencyIdString, tripIdString);
            BannedStopSet bannedStops;
            if (parts.length == 2) {
                bannedStops = BannedStopSet.ALL;
            } else {
                bannedStops = new BannedStopSet();
                for (int i = 2; i < parts.length; ++i) {
                    bannedStops.add(Integer.parseInt(parts[i]));
                }
            }
            bannedTripMap.put(tripId, bannedStops);
        }
        return bannedTripMap;
    }

/**
 * Gets the nth item in a list, or the item with the highest index if there are less than n 
 * elements, or the default value if the list is empty or null.
 * Throughout buildRequest() you will see the following idiom:
 * request.setParamX(get(paramX, n, request.getParamX));
 * 
 * This checks a query parameter field from Jersey (which is a list, one element for each occurrence
 * of the parameter in the query string) for the nth occurrence, or the one with the highest index.
 * If a parameter was supplied, it replaces the value in the RoutingRequest under construction 
 * (which was cloned from the prototypeRoutingRequest). If not, it uses the value already in that
 * RoutingRequest as a default (i.e. it uses the value cloned from the PrototypeRoutingRequest). 
 * 
 * @param l list of query parameter values
 * @param n requested item index 
 * @return nth item if it exists, closest existing item otherwise, or defaultValue if the list l 
 *         is null or empty.
 */
    private <T> T get(List<T> l, int n, T defaultValue) {
        if (l == null || l.size() == 0)
            return defaultValue;
        int maxIndex = l.size() - 1;
        if (n > maxIndex)
            n = maxIndex;
        T value = l.get(n);
        if (value instanceof Integer) {
            if (value.equals(-1)) {
                return defaultValue;
            }
        } else if (value instanceof Double) {
            if (value.equals(-1.0)) {
                return defaultValue;
            }
        }
        return value;
    }

}
