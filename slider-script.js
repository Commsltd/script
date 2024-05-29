document.addEventListener("DOMContentLoaded", function () {
    console.log("Document ready");

    if (typeof jQuery === "undefined") {
        console.error("jQuery is not loaded");
        return;
    }
    
    if (typeof jQuery.fn.slick === "undefined") {
        console.error("Slick is not loaded");
        return;
    }

    jQuery(function ($) {
        var $slider = $('.slideshow .slider'),
            maxItems = $('.item', $slider).length,
            dragging = false,
            tracking,
            rightTracking;

        var $sliderRight = $('.slideshow').clone().addClass('slideshow-right').appendTo($('.split-slideshow'));

        var rightItems = $('.item', $sliderRight).toArray();
        var reverseItems = rightItems.reverse();
        $('.slider', $sliderRight).html('');
        for (var i = 0; i < maxItems; i++) {
            $(reverseItems[i]).appendTo($('.slider', $sliderRight));
        }

        $slider.addClass('slideshow-left');
        $('.slideshow-left').slick({
            vertical: true,
            verticalSwiping: true,
            arrows: false,
            infinite: true,
            dots: true,
            speed: 1000,
            cssEase: 'cubic-bezier(0.7, 0, 0.3, 1)'
        }).on('beforeChange', function(event, slick, currentSlide, nextSlide) {
            if (currentSlide > nextSlide && nextSlide == 0 && currentSlide == maxItems - 1) {
                $('.slideshow-right .slider').slick('slickGoTo', -1);
                $('.slideshow-text').slick('slickGoTo', maxItems);
            } else if (currentSlide < nextSlide && currentSlide == 0 && nextSlide == maxItems - 1) {
                $('.slideshow-right .slider').slick('slickGoTo', maxItems);
                $('.slideshow-text').slick('slickGoTo', -1);
            } else {
                $('.slideshow-right .slider').slick('slickGoTo', maxItems - 1 - nextSlide);
                $('.slideshow-text').slick('slickGoTo', nextSlide);
            }
        }).on("mousewheel", function(event) {
            event.preventDefault();
            if (event.deltaX > 0 || event.deltaY < 0) {
                $(this).slick('slickNext');
            } else if (event.deltaX < 0 || event.deltaY > 0) {
                $(this).slick('slickPrev');
            };
        }).on('mousedown touchstart', function(){
            dragging = true;
            tracking = $('.slick-track', $slider).css('transform');
            tracking = parseInt(tracking.split(',')[5]);
            rightTracking = $('.slideshow-right .slick-track').css('transform');
            rightTracking = parseInt(rightTracking.split(',')[5]);
        }).on('mousemove touchmove', function(){
            if (dragging) {
                var newTracking = $('.slideshow-left .slick-track').css('transform');
                newTracking = parseInt(newTracking.split(',')[5]);
                var diffTracking = newTracking - tracking;
                $('.slideshow-right .slick-track').css({'transform': 'matrix(1, 0, 0, 1, 0, ' + (rightTracking - diffTracking) + ')'});
            }
        }).on('mouseleave touchend mouseup', function(){
            dragging = false;
        });

        $('.slideshow-right .slider').slick({
            swipe: false,
            vertical: true,
            arrows: false,
            infinite: true,
            speed: 950,
            cssEase: 'cubic-bezier(0.7, 0, 0.3, 1)',
            initialSlide: maxItems - 1
        });

        $('.slideshow-text').slick({
            swipe: false,
            vertical: true,
            arrows: false,
            infinite: true,
            speed: 900,
            cssEase: 'cubic-bezier(0.7, 0, 0.3, 1)'
        });
    });
});
