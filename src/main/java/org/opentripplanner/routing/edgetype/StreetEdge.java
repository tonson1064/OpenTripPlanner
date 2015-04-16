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

package org.opentripplanner.routing.edgetype;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.opentripplanner.common.TurnRestriction;
import org.opentripplanner.common.TurnRestrictionType;
import org.opentripplanner.common.geometry.CompactLineString;
import org.opentripplanner.common.geometry.DirectionUtils;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.util.ElevationUtils;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.util.BitSetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;

/**
 * This represents a street segment.
 * 
 * @author novalis
 * 
 */
public class StreetEdge extends Edge implements Cloneable {

    private static Logger LOG = LoggerFactory.getLogger(StreetEdge.class);

    private static final long serialVersionUID = 1L;

    /* TODO combine these with OSM highway= flags? */
    public static final int CLASS_STREET = 3;
    public static final int CLASS_CROSSING = 4;
    public static final int CLASS_OTHERPATH = 5;
    public static final int CLASS_OTHER_PLATFORM = 8;
    public static final int CLASS_TRAIN_PLATFORM = 16;
    public static final int ANY_PLATFORM_MASK = 24;
    public static final int CROSSING_CLASS_MASK = 7; // ignore platform
    public static final int CLASS_LINK = 32; // on/offramps; OSM calls them "links"

    private static final double GREENWAY_SAFETY_FACTOR = 0.1;

    // TODO(flamholz): do something smarter with the car speed here.
    public static final float DEFAULT_CAR_SPEED = 11.2f;

    /** If you have more than 8 flags, increase flags to short or int */
    private static final int BACK_FLAG_INDEX = 0;
    private static final int ROUNDABOUT_FLAG_INDEX = 1;
    private static final int HASBOGUSNAME_FLAG_INDEX = 2;
    private static final int NOTHRUTRAFFIC_FLAG_INDEX = 3;
    private static final int STAIRS_FLAG_INDEX = 4;
    private static final int SLOPEOVERRIDE_FLAG_INDEX = 5;
    private static final int WHEELCHAIR_ACCESSIBLE_FLAG_INDEX = 6;
    /*AGGIUNTA: flag per la presenza di strade per pedoni*/
    private static final int FOOTWAY_FLAG_INDEX = 7;
    /*AGGIUNTA: flag per indicare la presenza di un nodo bollard e resto*/
    
    
    private static final int CROSSING_FLAG_INDEX= 8;
    private static final int CONTAINS_BOLLARD_FLAG_INDEX = 9;
    private static final int CONTAINS_TURNSTILE_FLAG_INDEX = 10;
    private static final int CONTAINS_CYCLEBARRIER_FLAG_INDEX = 11;
    private static final int CONTAINS_TRAFFICLIGHT_SOUND_FLAG_INDEX = 12;
    private static final int CONTAINS_TRAFFICLIGHT_VIBRATION_FLAG_INDEX = 13;
    private static final int CONTAINS_TRAFFICLIGHT_FLOORVIBRATION_FLAG_INDEX = 14;
    
    
    /** back, roundabout, stairs, ... */
    private short flags;

    /**
     * Length is stored internally as 32-bit fixed-point (millimeters). This allows edges of up to ~2100km.
     * Distances used in calculations and exposed outside this class are still in double-precision floating point meters.
     * Someday we might want to convert everything to fixed point representations.
     */
    private int length_mm;

    /**
     * bicycleSafetyWeight = length * bicycleSafetyFactor. For example, a 100m street with a safety
     * factor of 2.0 will be considered in term of safety cost as the same as a 150m street with a
     * safety factor of 1.0.
     */
    protected float bicycleSafetyFactor;

    private int[] compactGeometry;
    
    private String name;

    private StreetTraversalPermission permission;

    private int streetClass = CLASS_OTHERPATH;
    
    /**
     * The speed (meters / sec) at which an automobile can traverse
     * this street segment.
     */
    private float carSpeed;

    /**
     * The angle at the start of the edge geometry.
     * Internal representation is -180 to +179 integer degrees mapped to -128 to +127 (brads)
     */
    private byte inAngle;

    /** The angle at the start of the edge geometry. Internal representation like that of inAngle. */
    private byte outAngle;

    public StreetEdge(StreetVertex v1, StreetVertex v2, LineString geometry,
                      String name, double length,
                      StreetTraversalPermission permission, boolean back) {
        super(v1, v2);
        this.setBack(back);
        this.setGeometry(geometry);
        this.length_mm = (int) (length * 1000); // CONVERT FROM FLOAT METERS TO FIXED MILLIMETERS
        this.bicycleSafetyFactor = 1.0f;
        this.name = name;
        this.setPermission(permission);
        this.setCarSpeed(DEFAULT_CAR_SPEED);
        this.setWheelchairAccessible(true); // accessible by default
        if (geometry != null) {
            try {
                for (Coordinate c : geometry.getCoordinates()) {
                    if (Double.isNaN(c.x)) {
                        System.out.println("X DOOM");
                    }
                    if (Double.isNaN(c.y)) {
                        System.out.println("Y DOOM");
                    }
                }
                // Conversion from radians to internal representation as a single signed byte.
                // We also reorient the angles since OTP seems to use South as a reference
                // while the azimuth functions use North.
                // FIXME Use only North as a reference, not a mix of North and South!
                // Range restriction happens automatically due to Java signed overflow behavior.
                // 180 degrees exists as a negative rather than a positive due to the integer range.
                double angleRadians = DirectionUtils.getLastAngle(geometry);
                outAngle = (byte) Math.round(angleRadians * 128 / Math.PI + 128);
                angleRadians = DirectionUtils.getFirstAngle(geometry);
                inAngle = (byte) Math.round(angleRadians * 128 / Math.PI + 128);
            } catch (IllegalArgumentException iae) {
                LOG.error("exception while determining street edge angles. setting to zero. there is probably something wrong with this street segment's geometry.");
                inAngle = 0;
                outAngle = 0;
            }
        }
    }

    public boolean canTraverse(RoutingRequest options) {
        if (options.wheelchairAccessible) {
            if (!isWheelchairAccessible()) {
                return false;
            }
            if (getMaxSlope() > options.maxSlope) {
                return false;
            }
        }
        
        return canTraverse(options.modes);
    }
    
    public boolean canTraverse(TraverseModeSet modes) {
        return getPermission().allows(modes);
    }
    
    private boolean canTraverse(RoutingRequest options, TraverseMode mode) {
        if (options.wheelchairAccessible) {
            if (!isWheelchairAccessible()) {
                return false;
            }
            if (getMaxSlope() > options.maxSlope) {
                return false;
            }
        }
        
        /*AGGIUNTA: controllo che l'arco non sia una footway, se è così non lo considero*/
        if(!options.permitFootway) {
        	if(isFootWay()) {
        		return false;
       		}
        }
        
        //AGGIUNTA: controllo se l'arco contiene una delle preferenze e non vogliamo attraversarla
        
        System.out.print("Contenuto delle opzioni dell'arco:\n"+         
				  "Crossing:" + options.permitCrossing + "\n" +
				  "Bollard:" + options.permitBollard + "\n" +
				  "Cyclebarrier:" + options.permitCycleBarrier + "\n" +
				  "Turnstile:" + options.permitTurnstile + "\n" +
				  "TrafficLightSound:" + options.permitTrafficLightSound + "\n" +
				  "TrafficLightVibration:" + options.permitTrafficLightVibration + "\n" +
				  "TrafficLightVibrationFloor:" + options.permitTrafficLightVibrationFloor + "\n\n");
        
        System.out.print("Tipo di arco:\n"+         
				  "Crossing:" + isCrossing() + "\n" +
				  "Bollard:" + containsBollard() + "\n" +
				  "Cyclebarrier:" + containsCycleBarrier() + "\n" +
				  "Turnstile:" + containsTurnstile() + "\n" +
				  "TrafficLightSound:" + containsTrafficLightSound() + "\n" +
				  "TrafficLightVibration:" + containsTrafficLightVibration() + "\n" +
				  "TrafficLightVibrationFloor:" + containsTrafficLightVibrationFloor() + "\n\n");

        
        if(   (options.permitCrossing==-1 && isCrossing()) 
        	||(options.permitBollard==-1 && containsBollard())
        	||(options.permitCycleBarrier==-1 && containsCycleBarrier())
        	||(options.permitTurnstile==-1 && containsTurnstile())
        	||(options.permitTrafficLightSound==-1 && containsTrafficLightSound())
        	||(options.permitTrafficLightVibration==-1 && containsTrafficLightVibration())
        	||(options.permitTrafficLightVibrationFloor==-1 && containsTrafficLightVibrationFloor())
        ) {
        	
        	return false;
        }
        	
        return getPermission().allows(mode);
    }

    public PackedCoordinateSequence getElevationProfile() {
        return null;
    }

    public boolean isElevationFlattened() {
        return false;
    }

    public float getMaxSlope() {
        return 0.0f;
    }

    @Override
    public double getDistance() {
        return length_mm / 1000.0; // CONVERT FROM FIXED MILLIMETERS TO FLOAT METERS
    }

    @Override
    public State traverse(State s0) {
        final RoutingRequest options = s0.getOptions();
        final TraverseMode currMode = s0.getNonTransitMode();
        StateEditor editor = doTraverse(s0, options, s0.getNonTransitMode());
        State state = (editor == null) ? null : editor.makeState();
        /* Kiss and ride support. Mode transitions occur without the explicit loop edges used in park-and-ride. */
        if (options.kissAndRide) {
            if (options.arriveBy) {
                // Branch search to "unparked" CAR mode ASAP after transit has been used.
                // Final WALK check prevents infinite recursion.
                if (s0.isCarParked() && s0.isEverBoarded() && currMode == TraverseMode.WALK) {
                    editor = doTraverse(s0, options, TraverseMode.CAR);
                    if (editor != null) {
                        editor.setCarParked(false); // Also has the effect of switching to CAR
                        State forkState = editor.makeState();
                        if (forkState != null) {
                            forkState.addToExistingResultChain(state);
                            return forkState; // return both parked and unparked states
                        }
                    }
                }
            } else { /* departAfter */
                // Irrevocable transition from driving to walking. "Parking" means being dropped off in this case.
                // Final CAR check needed to prevent infinite recursion.
                if ( ! s0.isCarParked() && ! getPermission().allows(TraverseMode.CAR) && currMode == TraverseMode.CAR) {
                    editor = doTraverse(s0, options, TraverseMode.WALK);
                    if (editor != null) {
                        editor.setCarParked(true); // has the effect of switching to WALK and preventing further car use
                        return editor.makeState(); // return only the "parked" walking state
                    }

                }
            }
        }
        return state;
    }

    /** return a StateEditor rather than a State so that we can make parking/mode switch modifications for kiss-and-ride. */
    private StateEditor doTraverse(State s0, RoutingRequest options, TraverseMode traverseMode) {
        boolean walkingBike = options.walkingBike;
        boolean backWalkingBike = s0.isBackWalkingBike();
        TraverseMode backMode = s0.getBackMode();
        Edge backEdge = s0.getBackEdge();
        if (backEdge != null) {
            // No illegal U-turns.
            // NOTE(flamholz): we check both directions because both edges get a chance to decide
            // if they are the reverse of the other. Also, because it doesn't matter which direction
            // we are searching in - these traversals are always disallowed (they are U-turns in one direction
            // or the other).
            // TODO profiling indicates that this is a hot spot.
            if (this.isReverseOf(backEdge) || backEdge.isReverseOf(this)) {
                return null;
            }
        }

        // Ensure we are actually walking, when walking a bike
        backWalkingBike &= TraverseMode.WALK.equals(backMode);
        walkingBike &= TraverseMode.WALK.equals(traverseMode);

        /* Check whether this street allows the current mode. If not and we are biking, attempt to walk the bike. */
        if (!canTraverse(options, traverseMode)) {
            if (traverseMode == TraverseMode.BICYCLE) {
                return doTraverse(s0, options.bikeWalkingOptions, TraverseMode.WALK);
            }
            return null;
        }

        // Automobiles have variable speeds depending on the edge type
        double speed = calculateSpeed(options, traverseMode);
        
        double time = getDistance() / speed;
        double weight;
        // TODO(flamholz): factor out this bike, wheelchair and walking specific logic to somewhere central.
        if (options.wheelchairAccessible) {
            weight = getSlopeSpeedEffectiveLength() / speed;
        } else if (traverseMode.equals(TraverseMode.BICYCLE)) {
            time = getSlopeSpeedEffectiveLength() / speed;
            switch (options.optimize) {
            case SAFE:
                weight = bicycleSafetyFactor * getDistance() / speed;
                break;
            case GREENWAYS:
                weight = bicycleSafetyFactor * getDistance() / speed;
                if (bicycleSafetyFactor <= GREENWAY_SAFETY_FACTOR) {
                    // greenways are treated as even safer than they really are
                    weight *= 0.66;
                }
                break;
            case FLAT:
                /* see notes in StreetVertex on speed overhead */
                weight = getDistance() / speed + getSlopeWorkCostEffectiveLength();
                break;
            case QUICK:
                weight = getSlopeSpeedEffectiveLength() / speed;
                break;
            case TRIANGLE:
                double quick = getSlopeSpeedEffectiveLength();
                double safety = bicycleSafetyFactor * getDistance();
                // TODO This computation is not coherent with the one for FLAT
                double slope = getSlopeWorkCostEffectiveLength();
                weight = quick * options.triangleTimeFactor + slope
                        * options.triangleSlopeFactor + safety
                        * options.triangleSafetyFactor;
                weight /= speed;
                break;
            default:
                weight = getDistance() / speed;
            }
        } else {
            if (walkingBike) {
                // take slopes into account when walking bikes
                time = getSlopeSpeedEffectiveLength() / speed;
            }
            weight = time;
            if (traverseMode.equals(TraverseMode.WALK)) {
            	
            	
            	
                // take slopes into account when walking
                // FIXME: this causes steep stairs to be avoided. see #1297.
                double costs = ElevationUtils.getWalkCostsForSlope(getDistance(), getMaxSlope());
                // as the cost walkspeed is assumed to be for 4.8km/h (= 1.333 m/sec) we need to adjust
                // for the walkspeed set by the user
                double elevationUtilsSpeed = 4.0 / 3.0;
                weight = costs * (elevationUtilsSpeed / speed);
                time = weight; //treat cost as time, as in the current model it actually is the same (this can be checked for maxSlope == 0)
                
                
                double mult=1.0;
                
                if(isCrossing())
                {
                	switch(options.permitCrossing)
                	{
                		case 0:
                			mult*=2.0;
                		break;
                		case 1:
                			mult*=1.0;
                		break;
                		case 2:
                			mult*=0.5;
                		break;
                		default:
                			mult*=1.0;
                		break;
                	}
                	
                
                	if(containsTrafficLightSound())
                	{
                		switch(options.permitTrafficLightSound)
                		{
                			case 0:
                				mult*=2.0;
                				break;
                			case 1:
                				mult*=1.0;
                				break;
                			case 2:
                				mult*=0.5;
                				break;
                			default:
                				mult*=1.0;
                				break;
                		}
                	}
                	if(containsTrafficLightVibration())
                    {
                    	switch(options.permitTrafficLightVibration)
                    	{
                    		case 0:
                    			mult*=2.0;
                    		break;
                    		case 1:
                    			mult*=1.0;
                    		break;
                    		case 2:
                    			mult*=0.5;
                    		break;
                    		default:
                    			mult*=1.0;
                    		break;
                    	}
                    	
                    }
                	
                	if(containsTrafficLightVibrationFloor())
                    {
                    	switch(options.permitTrafficLightVibrationFloor)
                    	{
                    		case 0:
                    			mult*=2.0;
                    		break;
                    		case 1:
                    			mult*=1.0;
                    		break;
                    		case 2:
                    			mult*=0.5;
                    		break;
                    		default:
                    			mult*=1.0;
                    		break;
                    	}
                    	
                    }
                	                	
                }
                
                if(containsBollard())
                {
                	switch(options.permitBollard)
                	{
                		case 0:
                			mult*=2.0;
                		break;
                		case 1:
                			mult*=1.0;
                		break;
                		case 2:
                			mult*=0.5;
                		break;
                		default:
                			mult*=1.0;
                		break;
                	}
                	
                }
                
                
                if(containsTurnstile())
                {
                	switch(options.permitTurnstile)
                	{
                		case 0:
                			mult*=2.0;
                		break;
                		case 1:
                			mult*=1.0;
                		break;
                		case 2:
                			mult*=0.5;
                		break;
                		default:
                			mult*=1.0;
                		break;
                	}
                	
                }
                
                if(containsCycleBarrier())
                {
                	switch(options.permitCycleBarrier)
                	{
                		case 0:
                			mult*=2.0;
                		break;
                		case 1:
                			mult*=1.0;
                		break;
                		case 2:
                			mult*=0.5;
                		break;
                		default:
                			mult*=1.0;
                		break;
                	}
                	
                }
                
                System.out.print("valore del moltiplicatore: "+ mult+ "\n");
                System.out.print("valore del peso dell'arco: "+ weight+"\n\n");
                
                weight*=mult;
                
                /*if(containsBollard())
            	{
            		System.out.print("Questa strada contiene una bollard, raddoppiato il peso\n");
            		weight=2*weight;
            	}*/
                
                /*
                // debug code
                if(weight > 100){
                    double timeflat = length / speed;
                    System.out.format("line length: %.1f m, slope: %.3f ---> slope costs: %.1f , weight: %.1f , time (flat):  %.1f %n", length, elevationProfile.getMaxSlope(), costs, weight, timeflat);
                }
                */
            }
        }

        if (isStairs()) {
            weight *= options.stairsReluctance;
        } else {
            // TODO: this is being applied even when biking or driving.
            weight *= options.walkReluctance;
        }

        StateEditor s1 = s0.edit(this);
        s1.setBackMode(traverseMode);
        s1.setBackWalkingBike(walkingBike);

        /* Compute turn cost. */
        StreetEdge backPSE;
        if (backEdge != null && backEdge instanceof StreetEdge) {
            backPSE = (StreetEdge) backEdge;
            RoutingRequest backOptions = backWalkingBike ?
                    s0.getOptions().bikeWalkingOptions : s0.getOptions();
            double backSpeed = backPSE.calculateSpeed(backOptions, backMode);
            final double realTurnCost;  // Units are seconds.

            // Apply turn restrictions
            if (options.arriveBy && !canTurnOnto(backPSE, s0, backMode)) {
                return null;
            } else if (!options.arriveBy && !backPSE.canTurnOnto(this, s0, traverseMode)) {
                return null;
            }

            /*
             * This is a subtle piece of code. Turn costs are evaluated differently during
             * forward and reverse traversal. During forward traversal of an edge, the turn
             * *into* that edge is used, while during reverse traversal, the turn *out of*
             * the edge is used.
             *
             * However, over a set of edges, the turn costs must add up the same (for
             * general correctness and specifically for reverse optimization). This means
             * that during reverse traversal, we must also use the speed for the mode of
             * the backEdge, rather than of the current edge.
             */
            if (options.arriveBy && tov instanceof IntersectionVertex) { // arrive-by search
                IntersectionVertex traversedVertex = ((IntersectionVertex) tov);

                realTurnCost = backOptions.getIntersectionTraversalCostModel().computeTraversalCost(
                        traversedVertex, this, backPSE, backMode, backOptions, (float) speed,
                        (float) backSpeed);
            } else if (!options.arriveBy && fromv instanceof IntersectionVertex) { // depart-after search
                IntersectionVertex traversedVertex = ((IntersectionVertex) fromv);

                realTurnCost = options.getIntersectionTraversalCostModel().computeTraversalCost(
                        traversedVertex, backPSE, this, traverseMode, options, (float) backSpeed,
                        (float) speed);                
            } else {
                // In case this is a temporary edge not connected to an IntersectionVertex
                LOG.debug("Not computing turn cost for edge {}", this);
                realTurnCost = 0; 
            }

            if (!traverseMode.isDriving()) {
                s1.incrementWalkDistance(realTurnCost / 100);  // just a tie-breaker
            }

            long turnTime = (long) Math.ceil(realTurnCost);
            time += turnTime;
            weight += options.turnReluctance * realTurnCost;
        }
        

        if (walkingBike || TraverseMode.BICYCLE.equals(traverseMode)) {
            if (!(backWalkingBike || TraverseMode.BICYCLE.equals(backMode))) {
                s1.incrementTimeInSeconds(options.bikeSwitchTime);
                s1.incrementWeight(options.bikeSwitchCost);
            }
        }

        if (!traverseMode.isDriving()) {
            s1.incrementWalkDistance(getDistance());
        }

        /* On the pre-kiss/pre-park leg, limit both walking and driving, either soft or hard. */
        int roundedTime = (int) Math.ceil(time);
        if (options.kissAndRide || options.parkAndRide) {
            if (options.arriveBy) {
                if (!s0.isCarParked()) s1.incrementPreTransitTime(roundedTime);
            } else {
                if (!s0.isEverBoarded()) s1.incrementPreTransitTime(roundedTime);
            }
            if (s1.isMaxPreTransitTimeExceeded(options)) {
                if (options.softPreTransitLimiting) {
                    weight += calculateOverageWeight(s0.getPreTransitTime(), s1.getPreTransitTime(),
                            options.maxPreTransitTime, options.preTransitPenalty,
                                    options.preTransitOverageRate);
                } else return null;
            }
        }
        
        /* Apply a strategy for avoiding walking too far, either soft (weight increases) or hard limiting (pruning). */
        if (s1.weHaveWalkedTooFar(options)) {

            // if we're using a soft walk-limit
            if( options.softWalkLimiting ){
                // just slap a penalty for the overage onto s1
                weight += calculateOverageWeight(s0.getWalkDistance(), s1.getWalkDistance(),
                        options.getMaxWalkDistance(), options.softWalkPenalty,
                                options.softWalkOverageRate);
            } else {
                // else, it's a hard limit; bail
                LOG.debug("Too much walking. Bailing.");
                return null;
            }
        }

        s1.incrementTimeInSeconds(roundedTime);
        
        s1.incrementWeight(weight);

        return s1;
    }

    private double calculateOverageWeight(double firstValue, double secondValue, double maxValue,
            double softPenalty, double overageRate) {
        // apply penalty if we stepped over the limit on this traversal
        boolean applyPenalty = false;
        double overageValue;

        if(firstValue <= maxValue && secondValue > maxValue){
            applyPenalty = true;
            overageValue = secondValue - maxValue;
        } else {
            overageValue = secondValue - firstValue;
        }

        // apply overage and add penalty if necessary
        return (overageRate * overageValue) + (applyPenalty ? softPenalty : 0.0);
    }

    /**
     * Calculate the average automobile traversal speed of this segment, given
     * the RoutingRequest, and return it in meters per second.
     */
    private double calculateCarSpeed(RoutingRequest options) {
        return getCarSpeed();
    }
    
    /**
     * Calculate the speed appropriately given the RoutingRequest and traverseMode.
     */
    public double calculateSpeed(RoutingRequest options, TraverseMode traverseMode) {
        if (traverseMode == null) {
            return Double.NaN;
        } else if (traverseMode.isDriving()) {
            // NOTE: Automobiles have variable speeds depending on the edge type
            return calculateCarSpeed(options);
        }
        return options.getSpeed(traverseMode);
    }

    @Override
    public double weightLowerBound(RoutingRequest options) {
        return timeLowerBound(options) * options.walkReluctance;
    }

    @Override
    public double timeLowerBound(RoutingRequest options) {
        return this.getDistance() / options.getStreetSpeedUpperBound();
    }

    public double getSlopeSpeedEffectiveLength() {
        return getDistance();
    }

    public double getSlopeWorkCostEffectiveLength() {
        return getDistance();
    }

    public void setBicycleSafetyFactor(float bicycleSafetyFactor) {
        this.bicycleSafetyFactor = bicycleSafetyFactor;
    }

    public float getBicycleSafetyFactor() {
        return bicycleSafetyFactor;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    public String toString() {
        return "StreetEdge(" + getId() + ", " + name + ", " + fromv + " -> " + tov
                + " length=" + this.getDistance() + " carSpeed=" + this.getCarSpeed()
                + " permission=" + this.getPermission() + ")";
    }

    @Override
    public StreetEdge clone() {
        try {
            return (StreetEdge) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
    
    public boolean canTurnOnto(Edge e, State state, TraverseMode mode) {
        for (TurnRestriction turnRestriction : getTurnRestrictions(state.getOptions().rctx.graph)) {
            /* FIXME: This is wrong for trips that end in the middle of turnRestriction.to
             */

            // NOTE(flamholz): edge to be traversed decides equivalence. This is important since 
            // it might be a temporary edge that is equivalent to some graph edge.
            if (turnRestriction.type == TurnRestrictionType.ONLY_TURN) {
                if (!e.isEquivalentTo(turnRestriction.to) && turnRestriction.modes.contains(mode) &&
                        turnRestriction.active(state.getTimeSeconds())) {
                    return false;
                }
            } else {
                if (e.isEquivalentTo(turnRestriction.to) && turnRestriction.modes.contains(mode) &&
                        turnRestriction.active(state.getTimeSeconds())) {
                    return false;
                }
            }
        }
        return true;
    }

	@Override
	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public LineString getGeometry() {
		return CompactLineString.uncompactLineString(fromv.getLon(), fromv.getLat(), tov.getLon(), tov.getLat(), compactGeometry, isBack());
	}

	private void setGeometry(LineString geometry) {
		this.compactGeometry = CompactLineString.compactLineString(fromv.getLon(), fromv.getLat(), tov.getLon(), tov.getLat(), isBack() ? (LineString)geometry.reverse() : geometry, isBack());
	}

	public void shareData(StreetEdge reversedEdge) {
	    if (Arrays.equals(compactGeometry, reversedEdge.compactGeometry)) {
	        compactGeometry = reversedEdge.compactGeometry;
	    } else {
	        LOG.warn("Can't share geometry between {} and {}", this, reversedEdge);
	    }
	}

	public boolean isWheelchairAccessible() {
		return BitSetUtils.get(flags, WHEELCHAIR_ACCESSIBLE_FLAG_INDEX);
	}

	public void setWheelchairAccessible(boolean wheelchairAccessible) {
        flags = BitSetUtils.set(flags, WHEELCHAIR_ACCESSIBLE_FLAG_INDEX, wheelchairAccessible);
	}
	
	public StreetTraversalPermission getPermission() {
		return permission;
	}

	public void setPermission(StreetTraversalPermission permission) {
		this.permission = permission;
	}

	public int getStreetClass() {
		return streetClass;
	}

	public void setStreetClass(int streetClass) {
		this.streetClass = streetClass;
	}

	/**
	 * Marks that this edge is the reverse of the one defined in the source
	 * data. Does NOT mean fromv/tov are reversed.
	 */
	public boolean isBack() {
	    return BitSetUtils.get(flags, BACK_FLAG_INDEX);
	}

	public void setBack(boolean back) {
            flags = BitSetUtils.set(flags, BACK_FLAG_INDEX, back);
	}

	public boolean isRoundabout() {
            return BitSetUtils.get(flags, ROUNDABOUT_FLAG_INDEX);
	}

	public void setRoundabout(boolean roundabout) {
	    flags = BitSetUtils.set(flags, ROUNDABOUT_FLAG_INDEX, roundabout);
	}

	public boolean hasBogusName() {
	    return BitSetUtils.get(flags, HASBOGUSNAME_FLAG_INDEX);
	}

	public void setHasBogusName(boolean hasBogusName) {
	    flags = BitSetUtils.set(flags, HASBOGUSNAME_FLAG_INDEX, hasBogusName);
	}

	public boolean isNoThruTraffic() {
            return BitSetUtils.get(flags, NOTHRUTRAFFIC_FLAG_INDEX);
	}

	public void setNoThruTraffic(boolean noThruTraffic) {
	    flags = BitSetUtils.set(flags, NOTHRUTRAFFIC_FLAG_INDEX, noThruTraffic);
	}

	/**
	 * This street is a staircase
	 */
	public boolean isStairs() {
            return BitSetUtils.get(flags, STAIRS_FLAG_INDEX);
	}

	public void setStairs(boolean stairs) {
	    flags = BitSetUtils.set(flags, STAIRS_FLAG_INDEX, stairs);
	}

	public float getCarSpeed() {
		return carSpeed;
	}

	public void setCarSpeed(float carSpeed) {
		this.carSpeed = carSpeed;
	}

	public boolean isSlopeOverride() {
	    return BitSetUtils.get(flags, SLOPEOVERRIDE_FLAG_INDEX);
	}

	public void setSlopeOverride(boolean slopeOverride) {
	    flags = BitSetUtils.set(flags, SLOPEOVERRIDE_FLAG_INDEX, slopeOverride);
	}

	/*AGGIUNTA: get e set per flag di footway*/
	public boolean isFootWay() {
		//System.out.print(flags+"\n");
		return BitSetUtils.get(flags, FOOTWAY_FLAG_INDEX);
	}

	public void setFootWay(boolean footWay) {
	    flags = BitSetUtils.set(flags, FOOTWAY_FLAG_INDEX, footWay);
	    //System.out.print(flags+"\n");
	}
	
	/*AGGIUNTA: get e set per flag di bollard*/
	public boolean containsBollard() {
		return BitSetUtils.get(flags, CONTAINS_BOLLARD_FLAG_INDEX);
	}
	
	public void setContainsBollard(boolean containsBollard) {
		flags = BitSetUtils.set(flags, CONTAINS_BOLLARD_FLAG_INDEX, containsBollard);
	}
	
	//AGGIUNTA: get e set per le preferenze
	
	
	public boolean isCrossing() {
		//System.out.print(flags+"\n");
		return BitSetUtils.get(flags, CROSSING_FLAG_INDEX);
	}

	public void setCrossing(boolean crossing) {
	    flags = BitSetUtils.set(flags, CROSSING_FLAG_INDEX, crossing);
	    //System.out.print(flags+"\n");
	}
	
	
	
	public boolean containsTurnstile() {
		return BitSetUtils.get(flags, CONTAINS_TURNSTILE_FLAG_INDEX);
	}
	
	public void setContainsTurnstile(boolean containsTurnstile) {
		flags = BitSetUtils.set(flags, CONTAINS_TURNSTILE_FLAG_INDEX, containsTurnstile);
	}
	
	public boolean containsCycleBarrier() {
		return BitSetUtils.get(flags, CONTAINS_CYCLEBARRIER_FLAG_INDEX);
	}
	
	public void setContainsCycleBarrier(boolean containsCycleBarrier) {
		flags = BitSetUtils.set(flags, CONTAINS_CYCLEBARRIER_FLAG_INDEX, containsCycleBarrier);
	}
	
	public boolean containsTrafficLightSound() {
		return BitSetUtils.get(flags, CONTAINS_TRAFFICLIGHT_SOUND_FLAG_INDEX);
	}
	
	public void setContainsTrafficLightSound(boolean containsTrafficLightSound) {
		flags = BitSetUtils.set(flags, CONTAINS_TRAFFICLIGHT_SOUND_FLAG_INDEX, containsTrafficLightSound);
	}
	
	public boolean containsTrafficLightVibration() {
		return BitSetUtils.get(flags, CONTAINS_TRAFFICLIGHT_VIBRATION_FLAG_INDEX);
	}
	
	public void setContainsTrafficLightVibration(boolean containsTrafficLightVibration) {
		flags = BitSetUtils.set(flags, CONTAINS_TRAFFICLIGHT_VIBRATION_FLAG_INDEX, containsTrafficLightVibration);
	}
	
	public boolean containsTrafficLightVibrationFloor() {
		return BitSetUtils.get(flags, CONTAINS_TRAFFICLIGHT_FLOORVIBRATION_FLAG_INDEX);
	}
	
	public void setContainsTrafficLightVibrationFloor(boolean containsTrafficLightVibrationFloor) {
		flags = BitSetUtils.set(flags, CONTAINS_TRAFFICLIGHT_FLOORVIBRATION_FLAG_INDEX, containsTrafficLightVibrationFloor);
	}
	
    /**
     * Return the azimuth of the first segment in this edge in integer degrees clockwise from South.
     * TODO change everything to clockwise from North
     */
	public int getInAngle() {
		return this.inAngle * 180 / 128;
	}

    /** Return the azimuth of the last segment in this edge in integer degrees clockwise from South. */
	public int getOutAngle() {
		return this.outAngle * 180 / 128;
	}

    protected List<TurnRestriction> getTurnRestrictions(Graph graph) {
        return graph.getTurnRestrictions(this);
    }
}
