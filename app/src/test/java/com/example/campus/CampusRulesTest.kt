package com.example.campus

import com.example.campus.data.*
import org.junit.Assert.*
import org.junit.Test

class CampusRulesTest {
    private fun delivery()=Delivery("d","Book","engineering","library",2.0,25.0,1000,5000,"student","Student",handoffCode="123456",distanceKm=0.2,createdAt=100)
    private fun route()=RouteOffer("r","engineering","library",3.0,2000,6000,"courier","Courier")

    @Test fun compatibleRouteMatchesBothEndpointsTimeAndCapacity() {
        assertTrue(CampusRules.routeMatches(delivery(),route()))
    }
    @Test fun overweightRequestIsNotMatched() {
        assertFalse(CampusRules.routeMatches(delivery().copy(weightKg=4.0),route()))
    }
    @Test fun adjacentWindowsDoNotOverlap() {
        assertFalse(CampusRules.routeMatches(delivery(),route().copy(windowStart=5000,windowEnd=8000)))
    }
    @Test fun requesterCannotMatchTheirOwnOffer() {
        assertFalse(CampusRules.routeMatches(delivery(),route().copy(courierId="student")))
    }
    @Test fun completedDeliveryCannotBeMatchedAgain() {
        assertFalse(CampusRules.routeMatches(delivery().copy(status="COMPLETED"),route()))
    }
    @Test fun routeGoingInReverseDoesNotMatch() {
        assertFalse(CampusRules.routeMatches(delivery(),route().copy(startId="library",endId="engineering")))
    }
    @Test fun campusDistanceIsSymmetricAndNonzero() {
        val a=CampusBuildings.get("engineering"); val b=CampusBuildings.get("library")
        assertEquals(CampusRules.distanceKm(a,b),CampusRules.distanceKm(b,a),0.0000001)
        assertTrue(CampusRules.distanceKm(a,b) in 0.1..1.0)
        assertEquals(0.0,CampusRules.distanceKm(a,a),0.0)
    }
    @Test fun deliverySavingsUseDocumentedCarBaseline() {
        assertEquals(0.342,CampusRules.deliveryCo2(2.0),0.0001)
        assertEquals(0.0,CampusRules.deliveryCo2(-1.0),0.0)
    }
    @Test fun rideAndSecondHandCategoriesHaveDifferentEstimates() {
        assertEquals(0.855,CampusRules.rideCo2(10.0),0.0001)
        assertTrue(CampusRules.marketplaceCo2("ELECTRONICS") > CampusRules.marketplaceCo2("BOOKS"))
    }
    @Test(expected=IllegalArgumentException::class)
    fun sameBuildingIsRejected() {
        CampusRules.validateDelivery("Book","library","library",1.0,10.0,1000,5000)
    }
    @Test(expected=IllegalArgumentException::class)
    fun nonFiniteWeightIsRejected() {
        CampusRules.validateDelivery("Book","engineering","library",Double.NaN,10.0,1000,5000)
    }
    @Test(expected=IllegalArgumentException::class)
    fun invertedWindowIsRejected() {
        CampusRules.validateDelivery("Book","engineering","library",1.0,10.0,5000,1000)
    }
}
